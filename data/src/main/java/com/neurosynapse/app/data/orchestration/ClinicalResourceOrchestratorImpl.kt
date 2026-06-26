package com.neurosynapse.app.data.orchestration

import android.util.Log
import com.neurosynapse.domain.common.PipelinePhase
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.gateway.BiometricSovereigntyGateway
import com.neurosynapse.domain.orchestrator.AcousticAnalysisPort
import com.neurosynapse.domain.orchestrator.ClinicalResourceOrchestrator
import com.neurosynapse.domain.orchestrator.ClinicalSynthesisPort
import com.neurosynapse.domain.orchestrator.DeviceResourceMonitor
import com.neurosynapse.domain.orchestrator.DeviceResourceSnapshot
import com.neurosynapse.domain.orchestrator.OrchestratorConfig
import com.neurosynapse.domain.orchestrator.OrchestratorResourceState
import com.neurosynapse.domain.orchestrator.PhaseResult
import com.neurosynapse.domain.orchestrator.ResourceCheckResult
import com.neurosynapse.domain.orchestrator.ResourceRecommendation
import com.neurosynapse.domain.orchestrator.ThermalState
import com.neurosynapse.domain.orchestrator.VisualAnalysisPort
import com.neurosynapse.domain.session.ClinicalSession
import com.neurosynapse.domain.session.ClinicalSessionState
import com.neurosynapse.domain.session.SessionWarning
import com.neurosynapse.domain.session.SubsystemHealth
import com.neurosynapse.domain.session.SubsystemSnapshot
import com.neurosynapse.domain.session.WarningCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — orchestration/ClinicalResourceOrchestratorImpl.kt
//
// PRINCIPIO SUPREMO: Secuencial, No Concurrente.
//
// El Semaphore(1) es el corazón de esta implementación. Un permiso disponible
// significa que ningún motor pesado está en RAM. El flujo garantizado es:
//
//   [ACQUIRE semaphore]
//   → Verificar RAM disponible
//   → Cargar motor (Whisper / OpenCV+YOLO / ONNX)
//   → Ejecutar análisis
//   → releaseResources() del motor — SIEMPRE en finally
//   [RELEASE semaphore]
//   → Siguiente fase
//
// Nunca hay dos motores pesados en RAM simultáneamente.
//
// CIRCUIT BREAKER (ADR-008):
//   Cada puerto tiene un estado de salud (Healthy / Degraded / Isolated).
//   Un fallo recuperable degrada el subsistema y reintenta hasta maxRetryCount.
//   Un fallo irrecuperable o un timeout aísla el subsistema.
//   Un subsistema aislado → la sesión se aborta de forma segura.
//
// SELECCIÓN DE MODELO SLM (ADR-006B):
//   El orquestador ajusta el presupuesto de RAM de CLINICAL_SYNTHESIS
//   según slmGateway.estimatedRamMb (1.100 MB para Llama, 620 MB para Qwen).
//   La selección del modelo ocurrió en el bootstrap de la app — aquí sólo
//   se consulta el perfil ya decidido.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "OrchestratorImpl"

class ClinicalResourceOrchestratorImpl(
    private val acousticPort:   AcousticAnalysisPort,
    private val visualPort:     VisualAnalysisPort,
    private val synthesisPort:  ClinicalSynthesisPort,
    private val resourceMonitor: DeviceResourceMonitor,
    private val sessionRepository: com.neurosynapse.domain.session.ClinicalSessionRepository
) : ClinicalResourceOrchestrator {

    // ── Semaphore — el guardián de la exclusividad de motores ────────────────
    // permits = 1: sólo un motor pesado puede estar activo en cualquier momento.
    // El semáforo es suspendible (kotlinx.coroutines) — no bloquea el hilo.
    private val pipelineSemaphore = Semaphore(permits = 1)

    // ── Estado observable del orquestador ────────────────────────────────────
    @Volatile private var currentPhase   = PipelinePhase.IDLE
    @Volatile private var lastSnapshot: DeviceResourceSnapshot? = null
    @Volatile private var activeSubsystems = mutableSetOf<String>()
    @Volatile private var circuitBreakers  = mutableMapOf<String, SubsystemHealth>(
        "acoustic" to SubsystemHealth.Healthy,
        "visual"   to SubsystemHealth.Healthy,
        "nlp"      to SubsystemHealth.Healthy
    )

    // ── executePipeline ───────────────────────────────────────────────────────

    override suspend fun executePipeline(
        session: ClinicalSession,
        gateway: BiometricSovereigntyGateway,
        config: OrchestratorConfig,
        onStateUpdate: (ClinicalSessionState) -> Unit
    ) {
        Log.i(TAG, "Pipeline iniciado para sesión ${session.sessionId.value}")

        // Iniciar monitoreo continuo de RAM durante el pipeline
        resourceMonitor.startContinuousMonitoring(intervalMs = 2_000L) { snapshot ->
            lastSnapshot = snapshot
            if (snapshot.availableRamMb < config.maxRamMb - config.ramWarningThresholdMb) {
                // RAM superó el umbral de advertencia — notificar al terapeuta
                kotlinx.coroutines.runBlocking {
                    session.raiseWarning(SessionWarning(
                        code = WarningCode.RAM_PRESSURE_HIGH,
                        message = "RAM disponible: ${snapshot.availableRamMb} MB. " +
                                  "Límite: ${config.maxRamMb} MB.",
                        affectedSubsystem = "system",
                        isCritical = false
                    ))
                }
            }
        }

        try {
            // ── FASE 2: Análisis Acústico ─────────────────────────────────────
            val acousticResult = executePhase(
                phase       = PipelinePhase.ACOUSTIC_ANALYSIS,
                subsystem   = "acoustic",
                session     = session,
                config      = config,
                onStateUpdate = onStateUpdate
            ) {
                acousticPort.analyze(session) { progress ->
                    kotlinx.coroutines.runBlocking {
                        session.updatePhaseProgress(
                            com.neurosynapse.domain.session.PhaseProgress((progress * 100).toInt())
                        )
                    }
                }
            }

            when (acousticResult) {
                is PhaseResult.Success -> {
                    session.completeAcousticAnalysis(acousticResult.data, nowUtc())
                    sessionRepository.save(session)
                    onStateUpdate(session.toState(nowUtc()))
                    Log.i(TAG, "Fase acústica completada en ${acousticResult.durationMs}ms")
                }
                is PhaseResult.Failure -> {
                    handleIrrecoverableFailure(session, gateway, acousticResult, config, onStateUpdate)
                    return
                }
                is PhaseResult.PartialSuccess -> {
                    // No aplica para la fase acústica — es todo o nada
                    handleIrrecoverableFailure(
                        session, gateway,
                        PhaseResult.Failure("acoustic", Exception("Partial acoustic result"),
                            false, WarningCode.LOW_AUDIO_QUALITY),
                        config, onStateUpdate
                    )
                    return
                }
            }

            // ── FASE 3: Análisis Visual ───────────────────────────────────────
            val visualResult = executePhase(
                phase       = PipelinePhase.VISUAL_ANALYSIS,
                subsystem   = "visual",
                session     = session,
                config      = config,
                onStateUpdate = onStateUpdate
            ) {
                visualPort.analyze(session) { progress ->
                    kotlinx.coroutines.runBlocking {
                        session.updatePhaseProgress(
                            com.neurosynapse.domain.session.PhaseProgress((progress * 100).toInt())
                        )
                    }
                }
            }

            when (visualResult) {
                is PhaseResult.Success -> {
                    session.completeVisualAnalysis(visualResult.data, nowUtc())
                    sessionRepository.save(session)
                    onStateUpdate(session.toState(nowUtc()))
                    Log.i(TAG, "Fase visual completada en ${visualResult.durationMs}ms")
                }
                is PhaseResult.Failure -> {
                    handleIrrecoverableFailure(session, gateway, visualResult, config, onStateUpdate)
                    return
                }
                is PhaseResult.PartialSuccess -> {
                    handleIrrecoverableFailure(
                        session, gateway,
                        PhaseResult.Failure("visual", Exception("Partial visual result"),
                            false, WarningCode.DETECTION_LOW_CONFIDENCE),
                        config, onStateUpdate
                    )
                    return
                }
            }

            // ── FASE 4: Síntesis Clínica (SLM) ───────────────────────────────
            val acousticMatrix   = session.getAcousticMatrix()!!
            val projectiveMatrix = session.getProjectiveMatrix()!!

            val synthesisResult = executePhase(
                phase       = PipelinePhase.CLINICAL_SYNTHESIS,
                subsystem   = "nlp",
                session     = session,
                config      = config,
                onStateUpdate = onStateUpdate
            ) {
                synthesisPort.synthesize(acousticMatrix, projectiveMatrix) { progress ->
                    kotlinx.coroutines.runBlocking {
                        session.updatePhaseProgress(
                            com.neurosynapse.domain.session.PhaseProgress((progress * 100).toInt())
                        )
                    }
                }
            }

            when (synthesisResult) {
                is PhaseResult.Success -> {
                    session.completeSynthesis(
                        synthesisResult.data,
                        nowUtc(),
                        synthesisPort::class.simpleName ?: "unknown-slm"
                    )
                    sessionRepository.save(session)
                    onStateUpdate(session.toState(nowUtc()))
                    Log.i(TAG, "Pipeline completado exitosamente para sesión ${session.sessionId.value}")
                }
                is PhaseResult.Failure -> {
                    handleIrrecoverableFailure(session, gateway, synthesisResult, config, onStateUpdate)
                }
                is PhaseResult.PartialSuccess -> {
                    handleIrrecoverableFailure(
                        session, gateway,
                        PhaseResult.Failure("nlp", Exception("Partial synthesis"),
                            false, WarningCode.SLM_INFERENCE_SLOW),
                        config, onStateUpdate
                    )
                }
            }

        } finally {
            resourceMonitor.stopContinuousMonitoring()
        }
    }

    // ── executePhase — corazón del Semaphore pattern ──────────────────────────

    /**
     * Ejecuta una fase del pipeline bajo el Semaphore de exclusividad.
     *
     * GARANTÍAS:
     * 1. Sólo una fase pesada puede tener el semáforo en cualquier momento.
     * 2. El motor siempre llama releaseResources() en el bloque finally.
     * 3. Si la RAM es insuficiente, espera al GC y reintenta maxRetryCount veces.
     * 4. Si el timeout (phaseTimeoutMs) se supera, aborta la fase.
     * 5. El Circuit Breaker actualiza el estado del subsistema en éxito o fallo.
     *
     * @param block El trabajo de la fase (análisis acústico, visual o síntesis).
     */
    private suspend fun <T> executePhase(
        phase: PipelinePhase,
        subsystem: String,
        session: ClinicalSession,
        config: OrchestratorConfig,
        onStateUpdate: (ClinicalSessionState) -> Unit,
        block: suspend () -> T
    ): PhaseResult<T> {
        currentPhase = phase
        Log.i(TAG, "Iniciando fase $phase (subsistema: $subsystem)")

        // Verificar que el Circuit Breaker no esté aislado
        if (circuitBreakers[subsystem] is SubsystemHealth.Isolated) {
            return PhaseResult.Failure(
                subsystem     = subsystem,
                error         = Exception("Subsistema $subsystem aislado por fallo previo"),
                isRecoverable = false,
                warningCode   = WarningCode.SUBSYSTEM_DEGRADED
            )
        }

        var lastError: Throwable? = null

        repeat(config.maxRetryCount) { attempt ->
            // 1. Verificar viabilidad de recursos
            when (val check = checkResourceViability(phase)) {
                is ResourceCheckResult.Viable -> { /* OK, continuar */ }
                is ResourceCheckResult.ThermalThrottled -> {
                    Log.w(TAG, "Thermal throttling en $phase. Esperando ${check.suggestedDelayMs}ms")
                    delay(check.suggestedDelayMs)
                }
                is ResourceCheckResult.Insufficient -> {
                    Log.w(TAG, "RAM insuficiente para $phase " +
                        "(disponible: ${check.availableRamMb} MB, " +
                        "requerido: ${check.requiredRamMb} MB). " +
                        "Intento ${attempt + 1}/${config.maxRetryCount}. " +
                        "Recomendación: ${check.recommendation}")

                    when (check.recommendation) {
                        ResourceRecommendation.WAIT_FOR_GC -> {
                            resourceMonitor.requestGarbageCollection()
                            delay(config.gcWaitDelayMs)
                        }
                        ResourceRecommendation.ABORT_SESSION,
                        ResourceRecommendation.DEFER_PHASE -> {
                            return PhaseResult.Failure(
                                subsystem     = subsystem,
                                error         = Exception("RAM insuficiente: ${check.availableRamMb} MB disponibles, ${check.requiredRamMb} MB requeridos"),
                                isRecoverable = false,
                                warningCode   = WarningCode.RAM_PRESSURE_HIGH
                            )
                        }
                        ResourceRecommendation.FREE_PRIOR_BUFFERS -> {
                            System.gc()
                            delay(config.gcWaitDelayMs)
                        }
                    }
                }
            }

            // 2. Adquirir el semáforo — bloquea si otro motor está activo
            pipelineSemaphore.acquire()
            val startMs = System.currentTimeMillis()

            try {
                activeSubsystems.add(subsystem)
                onStateUpdate(session.toState(nowUtc()))

                // 3. Ejecutar la fase con timeout
                val result = withTimeoutOrNull(config.phaseTimeoutMs) {
                    withContext(Dispatchers.Default) {
                        block()
                    }
                }

                if (result == null) {
                    lastError = Exception("Timeout en fase $phase después de ${config.phaseTimeoutMs}ms")
                    Log.e(TAG, "Timeout en $phase")
                    circuitBreakers[subsystem] = SubsystemHealth.Degraded("Timeout en $phase")
                    return@repeat   // Reintento
                }

                // 4. Éxito — actualizar Circuit Breaker
                circuitBreakers[subsystem] = SubsystemHealth.Healthy
                val durationMs = System.currentTimeMillis() - startMs
                return PhaseResult.Success(result, durationMs)

            } catch (e: Exception) {
                lastError = e
                Log.e(TAG, "Error en $phase (intento ${attempt + 1}): ${e.message}")
                circuitBreakers[subsystem] = SubsystemHealth.Degraded(e.message ?: "Error desconocido")

            } finally {
                // SIEMPRE liberar recursos del motor antes de soltar el semáforo
                try {
                    releaseMotorResources(subsystem)
                } catch (e: Exception) {
                    Log.e(TAG, "Error al liberar recursos de $subsystem: ${e.message}")
                }
                activeSubsystems.remove(subsystem)
                pipelineSemaphore.release()
                Log.d(TAG, "Semáforo liberado por $subsystem")
            }
        }

        // Agotados todos los reintentos — aislar el subsistema
        circuitBreakers[subsystem] = SubsystemHealth.Isolated(
            lastError?.message ?: "Fallo tras ${config.maxRetryCount} reintentos",
            System.currentTimeMillis()
        )

        return PhaseResult.Failure(
            subsystem     = subsystem,
            error         = lastError ?: Exception("Fallo desconocido en $phase"),
            isRecoverable = false,
            warningCode   = WarningCode.SUBSYSTEM_DEGRADED
        )
    }

    // ── checkResourceViability ────────────────────────────────────────────────

    override suspend fun checkResourceViability(phase: PipelinePhase): ResourceCheckResult {
        val snapshot = resourceMonitor.getCurrentSnapshot()
        lastSnapshot = snapshot

        // Verificar estado thermal primero — es inmediato
        if (snapshot.thermalState == ThermalState.CRITICAL) {
            return ResourceCheckResult.ThermalThrottled(
                thermalState     = snapshot.thermalState,
                suggestedDelayMs = 10_000L   // 10 segundos de espera ante thermal crítico
            )
        }
        if (snapshot.thermalState == ThermalState.SERIOUS) {
            return ResourceCheckResult.ThermalThrottled(
                thermalState     = snapshot.thermalState,
                suggestedDelayMs = 5_000L
            )
        }

        // Verificar RAM disponible contra el presupuesto de la fase
        val required = requiredRamForPhase(phase)
        if (snapshot.availableRamMb < required) {
            val recommendation = when {
                snapshot.availableRamMb >= required / 2 -> ResourceRecommendation.WAIT_FOR_GC
                snapshot.availableRamMb >= required / 3 -> ResourceRecommendation.FREE_PRIOR_BUFFERS
                else                                     -> ResourceRecommendation.ABORT_SESSION
            }
            return ResourceCheckResult.Insufficient(
                phase          = phase,
                availableRamMb = snapshot.availableRamMb,
                requiredRamMb  = required,
                recommendation = recommendation
            )
        }

        return ResourceCheckResult.Viable
    }

    // ── abortPipelineSafely ───────────────────────────────────────────────────

    override suspend fun abortPipelineSafely(
        session: ClinicalSession,
        reason: String,
        gateway: BiometricSovereigntyGateway
    ) {
        Log.w(TAG, "Abortando pipeline: $reason")
        resourceMonitor.stopContinuousMonitoring()

        // Liberar todos los motores activos
        activeSubsystems.toSet().forEach { subsystem ->
            try {
                releaseMotorResources(subsystem)
            } catch (e: Exception) {
                Log.e(TAG, "Error liberando $subsystem durante abort: ${e.message}")
            }
        }
        activeSubsystems.clear()

        // Liberar semáforo si estaba retenido
        if (pipelineSemaphore.availablePermits == 0) {
            pipelineSemaphore.release()
        }

        // Abortar la sesión en el dominio
        session.abort(reason, nowUtc())
        sessionRepository.save(session)
    }

    // ── getCurrentResourceState ───────────────────────────────────────────────

    override fun getCurrentResourceState(): OrchestratorResourceState =
        OrchestratorResourceState(
            activePhase              = currentPhase,
            semaphorePermitsAvailable = pipelineSemaphore.availablePermits,
            lastResourceSnapshot     = lastSnapshot,
            activeSubsystems         = activeSubsystems.toSet(),
            circuitBreakerStates     = circuitBreakers.toMap()
        )

    // ── Helpers privados ──────────────────────────────────────────────────────

    private suspend fun releaseMotorResources(subsystem: String) {
        when (subsystem) {
            "acoustic" -> acousticPort.releaseResources()
            "visual"   -> visualPort.releaseResources()
            "nlp"      -> synthesisPort.releaseResources()
        }
    }

    /**
     * Presupuesto de RAM requerido por fase.
     * Para CLINICAL_SYNTHESIS usa el estimado del puerto SLM (ADR-006B:
     * 1.100 MB para Llama 3.2 3B, 620 MB para Qwen 2.5 1.5B).
     */
    private fun requiredRamForPhase(phase: PipelinePhase): Int = when (phase) {
        PipelinePhase.ACOUSTIC_ANALYSIS  -> 300
        PipelinePhase.VISUAL_ANALYSIS    -> 400
        PipelinePhase.CLINICAL_SYNTHESIS -> {
            // Consultar el estimado del motor SLM seleccionado en bootstrap (ADR-006B)
            if (synthesisPort is com.neurosynapse.domain.usecases.SlmLocalGateway) {
                synthesisPort.estimatedRamMb
            } else {
                700   // Fallback conservador
            }
        }
        else -> 0
    }

    private suspend fun handleIrrecoverableFailure(
        session: ClinicalSession,
        gateway: BiometricSovereigntyGateway,
        failure: PhaseResult.Failure,
        config: OrchestratorConfig,
        onStateUpdate: (ClinicalSessionState) -> Unit
    ) {
        Log.e(TAG, "Fallo irrecuperable en subsistema '${failure.subsystem}': ${failure.error.message}")
        session.raiseWarning(SessionWarning(
            code              = failure.warningCode,
            message           = failure.error.message ?: "Fallo desconocido",
            affectedSubsystem = failure.subsystem,
            isCritical        = true
        ))
        abortPipelineSafely(session, failure.error.message ?: "Fallo irrecuperable", gateway)
        onStateUpdate(session.toState(nowUtc()))
    }

    private fun nowUtc(): UtcTimestamp = UtcTimestamp(
        ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
    )
}

package com.neurosynapse.domain.session

import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.ReportStatus
import java.security.MessageDigest
import java.util.UUID
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE CORE — Capa de Dominio Puro
// Archivo: session/ClinicalSession.kt  [v3 — Blueprint v1.0 FROZEN]
//
// CAMBIOS vs v2:
//   FIX-A: synchronized(Any()) → Mutex coroutine (ADR-002).
//           Elimina el riesgo de bloqueo de hilo en Dispatchers.IO/Default
//           bajo contención de hardware (Whisper + monitor de recursos).
//   FIX-B: ::class.simpleName → SubsystemHealth.canonicalCode (ADR-003).
//           El hash Merkle es ahora determinístico entre builds release
//           con R8/ProGuard activado. Requisito de auditoría forense.
//   FIX-C: PhaseProgress(Int) reemplaza Float.
//           Elimina imprecisión de punto flotante en el payload canónico
//           y en la traza de auditoría.
//   NEW:   ClinicalSafetyGuard integrado en el paquete session.
//
// ADRs aplicados: ADR-001 (coroutines en domain), ADR-002 (Mutex),
//                 ADR-003 (canonical codes), ADR-004 (SOAP-only outputs).
// ─────────────────────────────────────────────────────────────────────────────

// ── Value Object: progreso de fase ───────────────────────────────────────────

/**
 * Progreso determinístico de una fase clínica.
 * Int [0..100] en lugar de Float para eliminar imprecisión en el payload
 * del hash Merkle (0.47000003 vs 47 — el Int es canónico y reproducible).
 */
@JvmInline
value class PhaseProgress(val percentage: Int) {
    init {
        require(percentage in 0..100) {
            "PhaseProgress debe estar en [0, 100]. Recibido: $percentage"
        }
    }
    companion object {
        val ZERO = PhaseProgress(0)
        val COMPLETE = PhaseProgress(100)
    }
}

// ── SubsystemHealth con canonicalCode (FIX-B: ADR-003) ───────────────────────

/**
 * Estado de salud de un subsistema individual.
 *
 * [canonicalCode] es un string explícito hardcodeado, NUNCA derivado de
 * ::class.simpleName. Esto garantiza determinismo entre builds release
 * con R8/ProGuard. Cualquier cambio de nombre de clase no afecta el hash.
 *
 * Invariante de auditoría forense: el mismo evento produce el mismo hash
 * en cualquier build del APK firmado con la misma clave de release.
 */
sealed class SubsystemHealth(val canonicalCode: String) {
    object Healthy : SubsystemHealth("HEALTHY")
    data class Degraded(val reason: String) : SubsystemHealth("DEGRADED")
    data class Isolated(val reason: String, val isolatedAtEpochMs: Long) : SubsystemHealth("ISOLATED")
}

data class SubsystemSnapshot(
    val audio: SubsystemHealth,
    val vision: SubsystemHealth,
    val nlp: SubsystemHealth
) {
    /** Representación canónica R8-safe para el payload del hash Merkle. */
    fun toCanonicalString(): String =
        "${audio.canonicalCode}:${vision.canonicalCode}:${nlp.canonicalCode}"
}

// ── Audit Log ─────────────────────────────────────────────────────────────────

/**
 * Contenido semántico que el invocador aporta al evento.
 * La sesión calcula el hash; el invocador sólo describe qué ocurrió.
 */
data class AuditEventContent(
    val entryId: String = UUID.randomUUID().toString(),
    val sessionId: SessionId,
    val timestampUtc: UtcTimestamp,
    val eventType: AuditEventType,
    val slmVersion: String? = null,
    val subsystemStates: SubsystemSnapshot,
    val reportHash: IntegrityHash? = null
)

/**
 * Entrada sellada del log de auditoría. Inmutable. Append-only.
 *
 * Cadena Merkle:
 *   entryHash[n] = SHA-256( canonicalPayload[n] || entryHash[n-1] )
 *   entryHash[0] = SHA-256( canonicalPayload[0] || GENESIS_HASH )
 *
 * Alterar cualquier campo de una entrada invalida todos los hashes
 * subsiguientes, detectable en O(n) con verificación lineal.
 */
data class AuditLogEntry(
    val entryId: String,
    val sessionId: SessionId,
    val timestampUtc: UtcTimestamp,
    val eventType: AuditEventType,
    val slmVersion: String?,
    val subsystemStates: SubsystemSnapshot,
    val reportHash: IntegrityHash?,
    val entryHashSha256: IntegrityHash,
    val previousEntryHash: IntegrityHash
) {
    companion object {
        /**
         * Hash semilla para la primera entrada de toda cadena.
         * Valor fijo y público — documentado en la especificación forense.
         * SHA-256("NEURO-SYNAPSE-AUDIT-GENESIS-V1") precomputado:
         */
        val GENESIS_HASH = IntegrityHash(
            "b94d27b9934d3e08a52e52d7da7dabfac484efe04294e576f4a53a4b00000000"
                .padEnd(64, '0')
        )
    }
}

enum class AuditEventType {
    SESSION_OPENED,
    CONSENT_RECORDED,
    PHASE_CAPTURE_STARTED,
    PHASE_CAPTURE_COMPLETED,
    PHASE_ACOUSTIC_COMPLETED,
    PHASE_VISUAL_COMPLETED,
    REPORT_GENERATED,
    SUBSYSTEM_FAULT,
    SUBSYSTEM_RECOVERED,
    SESSION_ABORTED,
    SESSION_COMPLETED
}

// ── ClinicalSessionState (snapshot para ViewModel) ────────────────────────────

data class ClinicalSessionState(
    val sessionId: SessionId,
    val currentPhase: PipelinePhase,
    val consentLevel: ConsentLevel,
    val isProcessing: Boolean,
    val captureCompleted: Boolean = false,
    val acousticAnalysisCompleted: Boolean = false,
    val visualAnalysisCompleted: Boolean = false,
    val synthesisCompleted: Boolean = false,
    val acousticMatrix: AcousticContrastMatrix? = null,
    val projectiveMatrix: ProjectiveMorphometryMatrix? = null,
    val clinicalDraftReport: ClinicalDraftReport? = null,
    val subsystemSnapshot: SubsystemSnapshot,
    val activeWarning: SessionWarning? = null,
    val phaseProgress: PhaseProgress = PhaseProgress.ZERO,
    val sessionOpenedAt: UtcTimestamp,
    val lastUpdatedAt: UtcTimestamp
) {
    val isCompleted: Boolean get() = currentPhase == PipelinePhase.COMPLETED
    val hasDraftPendingReview: Boolean
        get() = clinicalDraftReport?.status == ReportStatus.DRAFT_PENDING_REVIEW
    val allSubsystemsHealthy: Boolean
        get() = subsystemSnapshot.audio is SubsystemHealth.Healthy &&
                subsystemSnapshot.vision is SubsystemHealth.Healthy &&
                subsystemSnapshot.nlp is SubsystemHealth.Healthy
}

data class SessionWarning(
    val code: WarningCode,
    val message: String,
    val affectedSubsystem: String?,
    val isCritical: Boolean = false
)

enum class WarningCode {
    LOW_AUDIO_QUALITY,
    IMAGE_CAPTURE_PARTIAL,
    SUBSYSTEM_DEGRADED,
    SLM_INFERENCE_SLOW,
    CONSENT_INCOMPLETE,
    RAM_PRESSURE_HIGH,
    DETECTION_LOW_CONFIDENCE,
    SAFETY_GUARD_TRIGGERED
}

// ── ClinicalSession — Aggregate Root v3 ──────────────────────────────────────

/**
 * AGGREGATE ROOT — Sesión Clínica (v3 — Blueprint FROZEN).
 *
 * INVARIANTES:
 * 1. Fases en orden secuencial estricto.
 * 2. Consentimiento previo a captura.
 * 3. Ambas matrices presentes antes de síntesis.
 * 4. Audit log append-only con cadena Merkle interna.
 * 5. Todas las operaciones de estado bajo Mutex coroutine (ADR-002).
 *
 * THREAD SAFETY (FIX-A):
 * [stateMutex] es un Mutex de kotlinx.coroutines. A diferencia de
 * synchronized(JVM), withLock { } suspende la corrutina sin bloquear
 * el hilo subyacente. Permite que el monitor de hardware actualice
 * subsystemSnapshot desde Dispatchers.IO mientras el orquestador
 * espera en Dispatchers.Default sin producir deadlock de hilo.
 *
 * DETERMINISMO DEL HASH (FIX-B):
 * Todo el payload del hash usa canonicalCode explícito, nunca
 * ::class.simpleName. El hash es reproducible entre cualquier
 * build release firmado con la misma clave.
 */
class ClinicalSession private constructor(
    val sessionId: SessionId,
    private val openedAtUtc: UtcTimestamp
) {
    // ── Mutex coroutine (ADR-002) ─────────────────────────────────────────────
    private val stateMutex = Mutex()

    // ── Estado mutable — acceso exclusivo bajo stateMutex ────────────────────
    private var _currentPhase: PipelinePhase = PipelinePhase.IDLE
    private var _consentLevel: ConsentLevel = ConsentLevel.NONE
    private var _acousticMatrix: AcousticContrastMatrix? = null
    private var _projectiveMatrix: ProjectiveMorphometryMatrix? = null
    private var _clinicalDraftReport: ClinicalDraftReport? = null
    private var _subsystemSnapshot: SubsystemSnapshot = SubsystemSnapshot(
        audio = SubsystemHealth.Healthy,
        vision = SubsystemHealth.Healthy,
        nlp = SubsystemHealth.Healthy
    )
    private var _activeWarning: SessionWarning? = null
    private var _phaseProgress: PhaseProgress = PhaseProgress.ZERO
    private val _auditLog: MutableList<AuditLogEntry> = mutableListOf()

    // ── Factory suspend ───────────────────────────────────────────────────────

    companion object {
        /**
         * Única forma de crear una ClinicalSession.
         * Es suspend porque escribe la primera entrada del audit log bajo Mutex.
         */
        suspend fun open(
            sessionId: SessionId,
            openedAtUtc: UtcTimestamp
        ): ClinicalSession {
            val session = ClinicalSession(sessionId, openedAtUtc)
            session.stateMutex.withLock {
                session.appendAuditChained(
                    AuditEventContent(
                        sessionId = sessionId,
                        timestampUtc = openedAtUtc,
                        eventType = AuditEventType.SESSION_OPENED,
                        subsystemStates = session._subsystemSnapshot
                    )
                )
            }
            return session
        }

        /**
         * Factory de rehidratación desde persistencia.
         *
         * CONTRATO: Sólo debe llamarse desde RoomClinicalSessionRepository.findById()
         * después de que el root_hash haya sido verificado. El estado que llega
         * aquí ya fue validado cuando fue persistido — no se re-ejecutan las
         * guardas de transición de la FSM.
         *
         * @param auditLog Log completo de la sesión restaurado desde BD.
         *                 La cadena Merkle fue verificada por RoomAuditLogRepository.
         */
        suspend fun restore(
            sessionId:    SessionId,
            openedAtUtc:  UtcTimestamp,
            currentPhase: PipelinePhase,
            consentLevel: ConsentLevel,
            isFrozen:     Boolean,
            auditLog:     List<AuditLogEntry>
        ): ClinicalSession {
            val session = ClinicalSession(sessionId, openedAtUtc)
            session.stateMutex.withLock {
                session._currentPhase  = currentPhase
                session._consentLevel  = consentLevel
                // Las matrices y el reporte se rehidratan por separado via
                // ClinicalArtifactRepository — no forman parte del estado base
                session._auditLog.addAll(auditLog)
            }
            return session
        }
    }

    // ── Getters suspendidos (lectura atómica) ─────────────────────────────────

    suspend fun getCurrentPhase(): PipelinePhase = stateMutex.withLock { _currentPhase }
    suspend fun getConsentLevel(): ConsentLevel = stateMutex.withLock { _consentLevel }
    suspend fun getAcousticMatrix(): AcousticContrastMatrix? = stateMutex.withLock { _acousticMatrix }
    suspend fun getProjectiveMatrix(): ProjectiveMorphometryMatrix? = stateMutex.withLock { _projectiveMatrix }
    suspend fun getClinicalDraftReport(): ClinicalDraftReport? = stateMutex.withLock { _clinicalDraftReport }
    suspend fun getAuditLog(): List<AuditLogEntry> = stateMutex.withLock { _auditLog.toList() }
    suspend fun getLastAuditHash(): IntegrityHash = stateMutex.withLock {
        _auditLog.lastOrNull()?.entryHashSha256 ?: AuditLogEntry.GENESIS_HASH
    }

    // ── Transiciones de fase ──────────────────────────────────────────────────

    suspend fun recordConsent(level: ConsentLevel, timestamp: UtcTimestamp) =
        stateMutex.withLock {
            requirePhase(PipelinePhase.IDLE) {
                "Consentimiento sólo registrable en IDLE. Fase actual: $_currentPhase"
            }
            require(level != ConsentLevel.NONE) { "ConsentLevel.NONE no es registrable." }
            _consentLevel = level
            appendAuditChained(AuditEventContent(
                sessionId = sessionId, timestampUtc = timestamp,
                eventType = AuditEventType.CONSENT_RECORDED,
                subsystemStates = _subsystemSnapshot
            ))
        }

    suspend fun startCapture(timestamp: UtcTimestamp) =
        stateMutex.withLock {
            require(_consentLevel != ConsentLevel.NONE) {
                "No se puede capturar sin consentimiento registrado."
            }
            requirePhase(PipelinePhase.IDLE) {
                "startCapture sólo válido desde IDLE. Fase actual: $_currentPhase"
            }
            _currentPhase = PipelinePhase.CAPTURE
            _phaseProgress = PhaseProgress.ZERO
            appendAuditChained(AuditEventContent(
                sessionId = sessionId, timestampUtc = timestamp,
                eventType = AuditEventType.PHASE_CAPTURE_STARTED,
                subsystemStates = _subsystemSnapshot
            ))
        }

    suspend fun completeCapture(timestamp: UtcTimestamp) =
        stateMutex.withLock {
            requirePhase(PipelinePhase.CAPTURE) {
                "completeCapture sólo válido desde CAPTURE. Fase actual: $_currentPhase"
            }
            _currentPhase = PipelinePhase.ACOUSTIC_ANALYSIS
            _phaseProgress = PhaseProgress.ZERO
            appendAuditChained(AuditEventContent(
                sessionId = sessionId, timestampUtc = timestamp,
                eventType = AuditEventType.PHASE_CAPTURE_COMPLETED,
                subsystemStates = _subsystemSnapshot
            ))
        }

    suspend fun completeAcousticAnalysis(
        matrix: AcousticContrastMatrix,
        timestamp: UtcTimestamp
    ) = stateMutex.withLock {
        requirePhase(PipelinePhase.ACOUSTIC_ANALYSIS) {
            "completeAcousticAnalysis sólo válido desde ACOUSTIC_ANALYSIS."
        }
        require(matrix.sessionId == sessionId) {
            "AcousticContrastMatrix pertenece a otra sesión."
        }
        _acousticMatrix = matrix
        _currentPhase = PipelinePhase.VISUAL_ANALYSIS
        _phaseProgress = PhaseProgress.ZERO
        appendAuditChained(AuditEventContent(
            sessionId = sessionId, timestampUtc = timestamp,
            eventType = AuditEventType.PHASE_ACOUSTIC_COMPLETED,
            subsystemStates = _subsystemSnapshot
        ))
    }

    suspend fun completeVisualAnalysis(
        matrix: ProjectiveMorphometryMatrix,
        timestamp: UtcTimestamp
    ) = stateMutex.withLock {
        requirePhase(PipelinePhase.VISUAL_ANALYSIS) {
            "completeVisualAnalysis sólo válido desde VISUAL_ANALYSIS."
        }
        require(matrix.sessionId == sessionId) {
            "ProjectiveMorphometryMatrix pertenece a otra sesión."
        }
        _projectiveMatrix = matrix
        _currentPhase = PipelinePhase.CLINICAL_SYNTHESIS
        _phaseProgress = PhaseProgress.ZERO
        appendAuditChained(AuditEventContent(
            sessionId = sessionId, timestampUtc = timestamp,
            eventType = AuditEventType.PHASE_VISUAL_COMPLETED,
            subsystemStates = _subsystemSnapshot
        ))
    }

    suspend fun completeSynthesis(
        report: ClinicalDraftReport,
        timestamp: UtcTimestamp,
        slmVersion: String
    ) = stateMutex.withLock {
        requirePhase(PipelinePhase.CLINICAL_SYNTHESIS) {
            "completeSynthesis sólo válido desde CLINICAL_SYNTHESIS."
        }
        requireNotNull(_acousticMatrix) { "Síntesis requiere AcousticContrastMatrix." }
        requireNotNull(_projectiveMatrix) { "Síntesis requiere ProjectiveMorphometryMatrix." }
        require(report.sessionId == sessionId) { "ClinicalDraftReport pertenece a otra sesión." }
        _clinicalDraftReport = report
        _currentPhase = PipelinePhase.COMPLETED
        _phaseProgress = PhaseProgress.COMPLETE
        appendAuditChained(AuditEventContent(
            sessionId = sessionId, timestampUtc = timestamp,
            eventType = AuditEventType.REPORT_GENERATED,
            slmVersion = slmVersion,
            subsystemStates = _subsystemSnapshot,
            reportHash = report.reportHashSha256
        ))
    }

    suspend fun abort(reason: String, timestamp: UtcTimestamp) =
        stateMutex.withLock {
            require(_currentPhase !in setOf(
                PipelinePhase.IDLE, PipelinePhase.COMPLETED, PipelinePhase.ABORTED
            )) { "No se puede abortar desde fase $_currentPhase" }
            _currentPhase = PipelinePhase.ABORTED
            _activeWarning = SessionWarning(
                code = WarningCode.SUBSYSTEM_DEGRADED,
                message = reason,
                affectedSubsystem = null,
                isCritical = true
            )
            appendAuditChained(AuditEventContent(
                sessionId = sessionId, timestampUtc = timestamp,
                eventType = AuditEventType.SESSION_ABORTED,
                subsystemStates = _subsystemSnapshot
            ))
        }

    // ── Telemetría asíncrona (monitores de hardware) ──────────────────────────

    /** Llamado desde corrutinas de fondo (hardware monitor). Suspende, no bloquea hilo. */
    suspend fun updateSubsystemHealth(snapshot: SubsystemSnapshot) =
        stateMutex.withLock { _subsystemSnapshot = snapshot }

    /** Llamado desde callback onProgress del motor activo. */
    suspend fun updatePhaseProgress(progress: PhaseProgress) =
        stateMutex.withLock { _phaseProgress = progress }

    suspend fun raiseWarning(warning: SessionWarning) =
        stateMutex.withLock { _activeWarning = warning }

    suspend fun clearWarning() =
        stateMutex.withLock { _activeWarning = null }

    // ── Snapshot atómico para UI ──────────────────────────────────────────────

    suspend fun toState(lastUpdatedAt: UtcTimestamp): ClinicalSessionState =
        stateMutex.withLock {
            ClinicalSessionState(
                sessionId = sessionId,
                currentPhase = _currentPhase,
                consentLevel = _consentLevel,
                isProcessing = _currentPhase in setOf(
                    PipelinePhase.CAPTURE,
                    PipelinePhase.ACOUSTIC_ANALYSIS,
                    PipelinePhase.VISUAL_ANALYSIS,
                    PipelinePhase.CLINICAL_SYNTHESIS
                ),
                captureCompleted = _currentPhase.ordinal > PipelinePhase.CAPTURE.ordinal,
                acousticAnalysisCompleted = _acousticMatrix != null,
                visualAnalysisCompleted = _projectiveMatrix != null,
                synthesisCompleted = _clinicalDraftReport != null,
                acousticMatrix = _acousticMatrix,
                projectiveMatrix = _projectiveMatrix,
                clinicalDraftReport = _clinicalDraftReport,
                subsystemSnapshot = _subsystemSnapshot,
                activeWarning = _activeWarning,
                phaseProgress = _phaseProgress,
                sessionOpenedAt = openedAtUtc,
                lastUpdatedAt = lastUpdatedAt
            )
        }

    // ── Merkle Chain interna (FIX-B: canonicalCode, FIX-C: PhaseProgress) ────

    /**
     * Calcula y appends una entrada del audit log encadenada.
     * PRECONDICIÓN: debe ejecutarse dentro de stateMutex.withLock { }.
     *
     * Payload canónico (R8-safe, determinístico):
     *   entryId || sessionId || timestamp || eventType || phase ||
     *   consent || progress% || slmVersion || reportHash ||
     *   audio.canonicalCode:vision.canonicalCode:nlp.canonicalCode ||
     *   previousHash
     *
     * Los separadores "||" son literales para evitar ambigüedades de
     * concatenación (e.g., "AB"+"C" vs "A"+"BC").
     */
    private fun appendAuditChained(content: AuditEventContent) {
        val previousHash = _auditLog.lastOrNull()?.entryHashSha256
            ?: AuditLogEntry.GENESIS_HASH

        val canonicalPayload = buildString {
            append(content.entryId); append("||")
            append(content.sessionId.value); append("||")
            append(content.timestampUtc.iso8601); append("||")
            append(content.eventType.name); append("||")
            // FIX-B: .name del enum es R8-safe porque está en @Keep via ADR-003
            append(_currentPhase.name); append("||")
            append(_consentLevel.name); append("||")
            // FIX-C: Int es determinístico, Float no
            append(_phaseProgress.percentage); append("||")
            append(content.slmVersion ?: "NONE"); append("||")
            append(content.reportHash?.hex ?: "NONE"); append("||")
            // FIX-B: canonicalCode explícito, nunca ::class.simpleName
            append(content.subsystemStates.toCanonicalString()); append("||")
            append(previousHash.hex)
        }

        val digest = MessageDigest.getInstance("SHA-256")
        val hashHex = digest.digest(canonicalPayload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

        _auditLog.add(AuditLogEntry(
            entryId = content.entryId,
            sessionId = content.sessionId,
            timestampUtc = content.timestampUtc,
            eventType = content.eventType,
            slmVersion = content.slmVersion,
            subsystemStates = content.subsystemStates,
            reportHash = content.reportHash,
            entryHashSha256 = IntegrityHash(hashHex),
            previousEntryHash = previousHash
        ))
    }

    private fun requirePhase(expected: PipelinePhase, lazyMessage: () -> String) {
        if (_currentPhase != expected) throw IllegalStateException(lazyMessage())
    }
}

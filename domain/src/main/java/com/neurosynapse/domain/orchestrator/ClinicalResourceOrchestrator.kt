package com.neurosynapse.domain.orchestrator

import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.PipelinePhase
import com.neurosynapse.domain.gateway.BiometricSovereigntyGateway
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.session.ClinicalSession
import com.neurosynapse.domain.session.ClinicalSessionState
import com.neurosynapse.domain.session.SessionWarning
import com.neurosynapse.domain.session.SubsystemHealth
import com.neurosynapse.domain.session.WarningCode
import com.neurosynapse.domain.synthesis.ClinicalDraftReport

data class DeviceResourceSnapshot(
    val availableRamMb: Int,
    val usedRamMb: Int,
    val totalRamMb: Int,
    val cpuLoadPercent: Float,
    val batteryLevelPercent: Int,
    val thermalState: ThermalState,
    val snapshotEpochMs: Long
) {
    val ramUsagePercent: Float get() = usedRamMb.toFloat() / totalRamMb * 100f

    fun canInitiatePhase(phase: PipelinePhase): Boolean = when (phase) {
        PipelinePhase.ACOUSTIC_ANALYSIS -> availableRamMb >= 300
        PipelinePhase.VISUAL_ANALYSIS   -> availableRamMb >= 400
        PipelinePhase.CLINICAL_SYNTHESIS -> availableRamMb >= 700
        else -> true
    }
}

enum class ThermalState { NOMINAL, FAIR, SERIOUS, CRITICAL }

sealed class ResourceCheckResult {
    object Viable : ResourceCheckResult()
    data class Insufficient(
        val phase: PipelinePhase,
        val availableRamMb: Int,
        val requiredRamMb: Int,
        val recommendation: ResourceRecommendation
    ) : ResourceCheckResult()
    data class ThermalThrottled(
        val thermalState: ThermalState,
        val suggestedDelayMs: Long
    ) : ResourceCheckResult()
}

enum class ResourceRecommendation {
    WAIT_FOR_GC, FREE_PRIOR_BUFFERS, DEFER_PHASE, ABORT_SESSION
}

data class OrchestratorConfig(
    val maxRamMb: Int = 1_500,
    val ramWarningThresholdMb: Int = 1_300,
    val maxRetryCount: Int = 3,
    val phaseTimeoutMs: Long = 120_000L,
    val gcWaitDelayMs: Long = 3_000L,
    val thermalThrottleDelayMs: Long = 5_000L
)

interface AcousticAnalysisPort {
    suspend fun analyze(session: ClinicalSession, onProgress: (Float) -> Unit): AcousticContrastMatrix
    suspend fun releaseResources()
}

interface VisualAnalysisPort {
    suspend fun analyze(session: ClinicalSession, onProgress: (Float) -> Unit): ProjectiveMorphometryMatrix
    suspend fun releaseResources()
}

interface ClinicalSynthesisPort {
    suspend fun synthesize(
        acousticMatrix: AcousticContrastMatrix,
        projectiveMatrix: ProjectiveMorphometryMatrix,
        onProgress: (Float) -> Unit
    ): ClinicalDraftReport
    suspend fun releaseResources()
}

interface DeviceResourceMonitor {
    suspend fun getCurrentSnapshot(): DeviceResourceSnapshot
    suspend fun requestGarbageCollection()
    fun startContinuousMonitoring(intervalMs: Long, onSnapshot: (DeviceResourceSnapshot) -> Unit)
    fun stopContinuousMonitoring()
}

interface ClinicalResourceOrchestrator {
    suspend fun executePipeline(
        session: ClinicalSession,
        gateway: BiometricSovereigntyGateway,
        config: OrchestratorConfig,
        onStateUpdate: (ClinicalSessionState) -> Unit
    )
    suspend fun checkResourceViability(phase: PipelinePhase): ResourceCheckResult
    suspend fun abortPipelineSafely(
        session: ClinicalSession,
        reason: String,
        gateway: BiometricSovereigntyGateway
    )
    fun getCurrentResourceState(): OrchestratorResourceState
}

data class OrchestratorResourceState(
    val activePhase: PipelinePhase,
    val semaphorePermitsAvailable: Int,
    val lastResourceSnapshot: DeviceResourceSnapshot?,
    val activeSubsystems: Set<String>,
    val circuitBreakerStates: Map<String, SubsystemHealth>
)

sealed class PhaseResult<out T> {
    data class Success<T>(val data: T, val durationMs: Long) : PhaseResult<T>()
    data class PartialSuccess<T>(
        val data: T,
        val warnings: List<SessionWarning>,
        val durationMs: Long
    ) : PhaseResult<T>()
    data class Failure(
        val subsystem: String,
        val error: Throwable,
        val isRecoverable: Boolean,
        val warningCode: WarningCode
    ) : PhaseResult<Nothing>()
}

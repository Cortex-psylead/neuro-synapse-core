package com.neurosynapse.domain.orchestrator

import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.gateway.BiometricSovereigntyGateway
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.session.*
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import java.io.File

interface AcousticAnalysisPort {
    suspend fun analyze(session: ClinicalSession, age: SubjectAge, sex: SubjectSex, onProgress: (Float) -> Unit): AcousticContrastMatrix
    suspend fun releaseResources()
}

interface VisualAnalysisPort {
    suspend fun analyze(session: ClinicalSession, testType: ProjectiveTestType, imageFiles: List<File>, age: SubjectAge, sex: SubjectSex, onProgress: (Float) -> Unit): ProjectiveMorphometryMatrix
    suspend fun releaseResources()
}

interface ClinicalSynthesisPort {
    suspend fun synthesize(acousticMatrix: AcousticContrastMatrix, projectiveMatrix: ProjectiveMorphometryMatrix, age: SubjectAge, sex: SubjectSex, activeModelPath: String?, onProgress: (Float) -> Unit): ClinicalDraftReport
    suspend fun releaseResources()
}

interface ClinicalResourceOrchestrator {
    suspend fun executePipeline(session: ClinicalSession, testType: ProjectiveTestType, imageFiles: List<File>, age: SubjectAge, sex: SubjectSex, activeModelPath: String?, gateway: BiometricSovereigntyGateway, config: OrchestratorConfig, onStateUpdate: (ClinicalSessionState) -> Unit)
    suspend fun checkResourceViability(phase: PipelinePhase): ResourceCheckResult
    suspend fun abortPipelineSafely(session: ClinicalSession, reason: String, gateway: BiometricSovereigntyGateway)
    fun getCurrentResourceState(): OrchestratorResourceState
}

data class OrchestratorConfig(val maxRamMb: Int = 1500)
sealed class ResourceCheckResult { object Viable : ResourceCheckResult() }
data class OrchestratorResourceState(val currentPhase: PipelinePhase, val permits: Int, val snapshot: Any?, val subsystems: Set<String>, val health: Map<String, SubsystemHealth>)

interface DeviceResourceMonitor {
    suspend fun getCurrentSnapshot(): DeviceResourceSnapshot
    suspend fun requestGarbageCollection()
    fun startContinuousMonitoring(intervalMs: Long, onSnapshot: (DeviceResourceSnapshot) -> Unit)
    fun stopContinuousMonitoring()
}

data class DeviceResourceSnapshot(
    val availableRamMb: Int,
    val usedRamMb: Int,
    val totalRamMb: Int,
    val cpuLoadPercent: Float,
    val batteryLevelPercent: Int,
    val thermalState: ThermalState,
    val snapshotEpochMs: Long
)

enum class ThermalState { NOMINAL, THROTTLING, CRITICAL, EMERGENCY, SHUTDOWN }


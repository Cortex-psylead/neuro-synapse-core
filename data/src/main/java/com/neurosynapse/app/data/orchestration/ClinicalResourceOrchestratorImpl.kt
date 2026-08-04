package com.neurosynapse.app.data.orchestration

import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.gateway.BiometricSovereigntyGateway
import com.neurosynapse.domain.orchestrator.*
import com.neurosynapse.domain.session.*
import java.io.File

class ClinicalResourceOrchestratorImpl(
    private val sessionRepo: ClinicalSessionRepository,
    private val resourceMonitor: DeviceResourceMonitor,
    private val acousticPort: AcousticAnalysisPort,
    private val visualPort: VisualAnalysisPort,
    private val synthesisPort: ClinicalSynthesisPort
) : ClinicalResourceOrchestrator {
    override suspend fun executePipeline(session: ClinicalSession, testType: ProjectiveTestType, imageFiles: List<File>, age: SubjectAge, sex: SubjectSex, activeModelPath: String?, gateway: BiometricSovereigntyGateway, config: OrchestratorConfig, onStateUpdate: (ClinicalSessionState) -> Unit) {
        val a = acousticPort.analyze(session, age, sex) {}
        val v = visualPort.analyze(session, testType, imageFiles, age, sex) {}
        synthesisPort.synthesize(a, v, age, sex, activeModelPath) {}
    }
    override suspend fun checkResourceViability(phase: PipelinePhase) = ResourceCheckResult.Viable
    override suspend fun abortPipelineSafely(session: ClinicalSession, reason: String, gateway: BiometricSovereigntyGateway) {}
    override fun getCurrentResourceState() = OrchestratorResourceState(PipelinePhase.IDLE, 1, null, emptySet(), emptyMap())
}

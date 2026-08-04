package com.neurosynapse.app.data.engines

import com.neurosynapse.domain.orchestrator.*
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.ReportStatus
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.common.SchemaVersion
import com.neurosynapse.domain.synthesis.SoapSubjective
import com.neurosynapse.domain.synthesis.SoapObjective
import com.neurosynapse.domain.synthesis.SoapAssessment
import com.neurosynapse.domain.synthesis.SoapPlan
import com.neurosynapse.domain.synthesis.SlmGenerationMetadata
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.DurationMs
import com.neurosynapse.domain.common.ProjectiveTestType
import com.neurosynapse.domain.session.ClinicalSession

class StubAcousticEngine : AcousticAnalysisPort {
    override suspend fun analyze(
        session: ClinicalSession,
        age: com.neurosynapse.domain.common.SubjectAge,
        sex: com.neurosynapse.domain.common.SubjectSex,
        onProgress: (Float) -> Unit
    ): AcousticContrastMatrix {
        return AcousticContrastMatrix(
            sessionId = session.sessionId,
            acquisitionTimestampUtc = UtcTimestamp.now(),
            baselineChannel = com.neurosynapse.domain.acoustic.BaselineAudioChannel(
                durationSeconds = 10.0,
                stimulusHashSha256 = IntegrityHash("abc"),
                acousticSignature = com.neurosynapse.domain.acoustic.AcousticSignature(
                    com.neurosynapse.domain.common.FrequencyHz(100f), 10.0,
                    com.neurosynapse.domain.common.FrequencyHz(50f), com.neurosynapse.domain.common.FrequencyHz(200f),
                    com.neurosynapse.domain.common.ClinicalPercent(0.1f), 0.05,
                    com.neurosynapse.domain.common.Decibels(60f), 0.02,
                    com.neurosynapse.domain.common.Decibels(20f), 120, 5,
                    DurationMs(100)
                )
            ),
            activeChannel = com.neurosynapse.domain.acoustic.ActiveAudioChannel(
                triggerCategory = com.neurosynapse.domain.common.TriggerCategory.PROSODIC_ANOMALY,
                durationSeconds = 10.0,
                acousticSignature = com.neurosynapse.domain.acoustic.AcousticSignature(
                    com.neurosynapse.domain.common.FrequencyHz(100f), 10.0,
                    com.neurosynapse.domain.common.FrequencyHz(50f), com.neurosynapse.domain.common.FrequencyHz(200f),
                    com.neurosynapse.domain.common.ClinicalPercent(0.1f), 0.05,
                    com.neurosynapse.domain.common.Decibels(60f), 0.02,
                    com.neurosynapse.domain.common.Decibels(20f), 120, 5,
                    DurationMs(100)
                )
            ),
            contrastDeltas = com.neurosynapse.domain.acoustic.AcousticContrastDeltas(
                0.1, com.neurosynapse.domain.common.ScaleFactor(1.1f), com.neurosynapse.domain.common.ScaleFactor(1.1f),
                2.0, 0.05, 0.1, com.neurosynapse.domain.common.NormalizedIndex(0.5f), com.neurosynapse.domain.common.ClinicalFlag(false)
            ),
            processingMetadata = com.neurosynapse.domain.acoustic.AcousticProcessingMetadata("stub", "1.0", "arm64", DurationMs(100), IntegrityHash("hash"))
        )
    }
    override suspend fun releaseResources() {}
}

class StubVisualEngine : VisualAnalysisPort {
    override suspend fun analyze(
        session: ClinicalSession,
        testType: ProjectiveTestType,
        imageFiles: List<java.io.File>,
        age: com.neurosynapse.domain.common.SubjectAge,
        sex: com.neurosynapse.domain.common.SubjectSex,
        onProgress: (Float) -> Unit
    ): ProjectiveMorphometryMatrix {
        val dummyResult = com.neurosynapse.domain.projective.ProjectiveTestResult.HtpHouseResult(
            detectedElements = emptyList(),
            globalMorphometrics = com.neurosynapse.domain.projective.GlobalMorphometrics(
                com.neurosynapse.domain.projective.OccupancyRatio(0.0), 0.0, 0.0, 
                com.neurosynapse.domain.projective.NormalizedCoord(0.0), com.neurosynapse.domain.projective.NormalizedCoord(0.0), 0.0
            ),
            imageHashSha256 = IntegrityHash("hash"),
            houseMetrics = com.neurosynapse.domain.projective.HouseMetrics(false, false, false, 0, com.neurosynapse.domain.projective.OccupancyRatio(0.0), 0.0, false, false, false)
        )
        return ProjectiveMorphometryMatrix(
            sessionId = session.sessionId,
            acquisitionTimestampUtc = UtcTimestamp.now(),
            testResults = listOf(dummyResult),
            processingEngine = "stub",
            processingDurationMs = DurationMs(100),
            integrityHashSha256 = IntegrityHash("hash")
        )
    }
    override suspend fun releaseResources() {}
}

class StubSynthesisEngine : ClinicalSynthesisPort {
    override suspend fun synthesize(
        acousticMatrix: AcousticContrastMatrix,
        projectiveMatrix: ProjectiveMorphometryMatrix,
        age: com.neurosynapse.domain.common.SubjectAge,
        sex: com.neurosynapse.domain.common.SubjectSex,
        activeModelPath: String?,
        onProgress: (Float) -> Unit
    ): ClinicalDraftReport {
        return ClinicalDraftReport(
            sessionId = SessionId("test"),
            generatedAtUtc = UtcTimestamp.now(),
            subjective = SoapSubjective("narrative", emptyList(), "calm", emptyList()),
            objective = SoapObjective(
                com.neurosynapse.domain.synthesis.AcousticSummaryForReport(
                    com.neurosynapse.domain.common.ClinicalFlag(false),
                    com.neurosynapse.domain.common.NormalizedIndex(0.1f),
                    emptyList()
                ),
                com.neurosynapse.domain.synthesis.ProjectiveSummaryForReport(emptyList(), emptyList(), emptyList()),
                IntegrityHash("hash"),
                IntegrityHash("hash")
            ),
            assessment = SoapAssessment(emptyList(), emptyList(), emptyList(), emptyList()),
            plan = SoapPlan(emptyList(), emptyList(), emptyList(), "freq"),
            slmMetadata = SlmGenerationMetadata("model", "1", "t1", DurationMs(100), 10, 10, 0.7),
            reportHashSha256 = IntegrityHash("hash")
        )
    }
    override suspend fun releaseResources() {}
}

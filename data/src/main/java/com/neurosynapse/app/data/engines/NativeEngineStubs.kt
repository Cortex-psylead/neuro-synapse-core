package com.neurosynapse.app.data.engines

import android.util.Log
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.ClinicalFlag
import com.neurosynapse.domain.common.ClinicalPercent
import com.neurosynapse.domain.common.Decibels
import com.neurosynapse.domain.common.DurationMs
import com.neurosynapse.domain.common.FrequencyHz
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.NormalizedIndex
import com.neurosynapse.domain.common.ScaleFactor
import com.neurosynapse.domain.common.SchemaVersion
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.TriggerCategory
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.orchestrator.AcousticAnalysisPort
import com.neurosynapse.domain.orchestrator.ClinicalSynthesisPort
import com.neurosynapse.domain.orchestrator.VisualAnalysisPort
import com.neurosynapse.domain.projective.GlobalMorphometrics
import com.neurosynapse.domain.projective.NormalizedCoord
import com.neurosynapse.domain.projective.OccupancyRatio
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.session.ClinicalSession
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.HypothesisConfidence
import com.neurosynapse.domain.synthesis.ReportStatus
import com.neurosynapse.domain.synthesis.SlmGenerationMetadata
import com.neurosynapse.domain.synthesis.SoapAssessment
import com.neurosynapse.domain.synthesis.SoapObjective
import com.neurosynapse.domain.synthesis.SoapPlan
import com.neurosynapse.domain.synthesis.SoapSubjective
import com.neurosynapse.domain.synthesis.AcousticSummaryForReport
import com.neurosynapse.domain.synthesis.ClinicalHypothesis
import com.neurosynapse.domain.synthesis.ProjectiveSummaryForReport
import com.neurosynapse.domain.usecases.SlmLocalGateway
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — engines/NativeEngineStubs.kt
//
// Stubs compilables de los tres motores nativos del pipeline clínico.
//
// PROPÓSITO:
//   Permiten que ClinicalResourceOrchestratorImpl compile y que el pipeline
//   completo sea testeable de extremo a extremo con datos determinísticos,
//   ANTES de que los motores nativos (Whisper JNI, OpenCV, ONNX) estén listos.
//
// PLAN DE REEMPLAZO (Sprints 3-5):
//   StubAcousticEngine  → WhisperPraatAcousticEngine  (Sprint 3 — Spike JNI)
//   StubVisualEngine    → OpenCvYoloVisualEngine       (Sprint 4)
//   StubSynthesisEngine → OnnxQwenSlmGateway /
//                         OnnxLlamaSlmGateway          (Sprint 5)
//
// Los stubs retornan datos determinísticos que reflejan un caso clínico
// plausible de ansiedad leve — útiles para tests de integración del pipeline.
// ─────────────────────────────────────────────────────────────────────────────

// ── Stub: Motor Acústico ──────────────────────────────────────────────────────

/**
 * Stub del motor acústico. Simula el comportamiento de Whisper + Praat
 * con datos determinísticos de ansiedad leve para tests de integración.
 *
 * REEMPLAZAR POR: WhisperPraatAcousticEngine en Sprint 3.
 */
class StubAcousticEngine : AcousticAnalysisPort {

    override suspend fun analyze(
        session: ClinicalSession,
        onProgress: (Float) -> Unit
    ): AcousticContrastMatrix {
        Log.w("StubAcoustic", "⚠️ STUB activo — reemplazar con WhisperPraatAcousticEngine en Sprint 3")

        // Simular progreso del análisis
        onProgress(0.0f);   kotlinx.coroutines.delay(200)
        onProgress(0.25f);  kotlinx.coroutines.delay(200)
        onProgress(0.5f);   kotlinx.coroutines.delay(200)
        onProgress(0.75f);  kotlinx.coroutines.delay(200)
        onProgress(1.0f)

        val now = nowUtc()
        val baselineSignature = com.neurosynapse.domain.acoustic.AcousticSignature(
            f0MeanHz             = FrequencyHz(182.4),
            f0StdDevHz           = 12.1,
            f0MinHz              = FrequencyHz(145.0),
            f0MaxHz              = FrequencyHz(230.5),
            jitterLocalPercent   = ClinicalPercent(0.82),
            jitterRap            = 0.54,
            shimmerLocalDb       = Decibels(0.31),
            shimmerApq3          = 0.22,
            hnrDb                = Decibels(18.7),
            speechRateWpm        = 142,
            pauseCount           = 3,
            pauseMeanDurationMs  = DurationMs(310L)
        )
        val activeSignature = com.neurosynapse.domain.acoustic.AcousticSignature(
            f0MeanHz             = FrequencyHz(197.1),
            f0StdDevHz           = 18.3,
            f0MinHz              = FrequencyHz(150.0),
            f0MaxHz              = FrequencyHz(255.0),
            jitterLocalPercent   = ClinicalPercent(1.47),
            jitterRap            = 0.97,
            shimmerLocalDb       = Decibels(0.58),
            shimmerApq3          = 0.41,
            hnrDb                = Decibels(14.2),
            speechRateWpm        = 168,
            pauseCount           = 6,
            pauseMeanDurationMs  = DurationMs(180L)
        )
        val hash = sha256("stub-acoustic-${session.sessionId.value}-${now.iso8601}")
        return AcousticContrastMatrix(
            sessionId                = session.sessionId,
            schemaVersion            = SchemaVersion.ACOUSTIC_MATRIX_V1,
            acquisitionTimestampUtc  = now,
            baselineChannel          = com.neurosynapse.domain.acoustic.BaselineAudioChannel(
                durationSeconds      = 45.2,
                stimulusHashSha256   = IntegrityHash("a".repeat(64)),
                acousticSignature    = baselineSignature
            ),
            activeChannel            = com.neurosynapse.domain.acoustic.ActiveAudioChannel(
                triggerCategory      = TriggerCategory.ANXIETY,
                durationSeconds      = 67.8,
                acousticSignature    = activeSignature
            ),
            contrastDeltas           = com.neurosynapse.domain.acoustic.AcousticContrastDeltas(
                f0ElevationPercent             = 8.6,
                jitterIncreaseFactor           = ScaleFactor(1.79),
                shimmerIncreaseFactor          = ScaleFactor(1.87),
                hnrDegradationDb               = -4.5,
                speechRateAccelerationPercent  = 18.3,
                pauseDensityRatio              = 3.67,
                compositeStressIndex           = NormalizedIndex(0.74),
                clinicalFlag                   = ClinicalFlag.ELEVATED_AUTONOMIC_ACTIVATION
            ),
            processingMetadata = com.neurosynapse.domain.acoustic.AcousticProcessingMetadata(
                engine               = "stub-whisper-praat",
                engineVersion        = "0.0.1-stub",
                deviceArmSoc         = "Stub/CI",
                processingDurationMs = DurationMs(800L),
                integrityHashSha256  = IntegrityHash(hash)
            )
        )
    }

    override suspend fun releaseResources() {
        Log.d("StubAcoustic", "releaseResources() (stub — sin recursos nativos)")
    }
}

// ── Stub: Motor Visual ────────────────────────────────────────────────────────

/**
 * Stub del motor visual. Simula OpenCV + YOLOv8n con datos determinísticos.
 * REEMPLAZAR POR: OpenCvYoloVisualEngine en Sprint 4.
 */
class StubVisualEngine : VisualAnalysisPort {

    override suspend fun analyze(
        session: ClinicalSession,
        onProgress: (Float) -> Unit
    ): ProjectiveMorphometryMatrix {
        Log.w("StubVisual", "⚠️ STUB activo — reemplazar con OpenCvYoloVisualEngine en Sprint 4")

        onProgress(0.0f);   kotlinx.coroutines.delay(150)
        onProgress(0.33f);  kotlinx.coroutines.delay(150)
        onProgress(0.66f);  kotlinx.coroutines.delay(150)
        onProgress(1.0f)

        val now  = nowUtc()
        val hash = sha256("stub-visual-${session.sessionId.value}-${now.iso8601}")

        val globalMetrics = GlobalMorphometrics(
            traceOccupancyRatio    = OccupancyRatio(0.42),
            strokeDensityScore     = 0.65,
            symmetryIndex          = 0.71,
            centerOfMassX          = NormalizedCoord(0.52),
            centerOfMassY          = NormalizedCoord(0.48),
            contourComplexityScore = 0.38
        )
        val houseResult = com.neurosynapse.domain.projective.ProjectiveTestResult.HtpHouseResult(
            detectedElements   = emptyList(),
            globalMorphometrics = globalMetrics,
            imageHashSha256    = IntegrityHash("b".repeat(64)),
            houseMetrics       = com.neurosynapse.domain.projective.HouseMetrics(
                hasChimney = true, hasDoor = true, hasWindows = true,
                windowCount = 2, roofOccupancyRatio = OccupancyRatio(0.35),
                doorCentrality = 0.82, chimneySmoke = false,
                hasPath = true, pathConnectsToDoor = true
            )
        )
        return ProjectiveMorphometryMatrix(
            sessionId               = session.sessionId,
            schemaVersion           = SchemaVersion.PROJECTIVE_MATRIX_V1,
            acquisitionTimestampUtc = now,
            testResults             = listOf(houseResult),
            processingEngine        = "stub-opencv-yolov8n",
            processingDurationMs    = DurationMs(1_200L),
            integrityHashSha256     = IntegrityHash(hash)
        )
    }

    override suspend fun releaseResources() {
        Log.d("StubVisual", "releaseResources() (stub — sin recursos nativos)")
    }
}

// ── Stub: Motor SLM ───────────────────────────────────────────────────────────

/**
 * Stub del motor SLM. Simula inferencia ONNX con un reporte SOAP determinístico.
 * REEMPLAZAR POR: OnnxQwenSlmGateway / OnnxLlamaSlmGateway en Sprint 5.
 *
 * Implementa SlmLocalGateway para que el orquestador pueda consultar
 * estimatedRamMb y ajustar el presupuesto de RAM de la fase de síntesis.
 */
class StubSynthesisEngine : ClinicalSynthesisPort, SlmLocalGateway {

    override val modelId: String = "stub-slm-0.0.1"
    override val slmProfile = com.neurosynapse.app.data.engines.slm.SlmProfile.LEGACY
    override val estimatedRamMb: Int = 100   // Stub no usa RAM real

    override suspend fun warmup() = com.neurosynapse.domain.usecases.WarmupResult(
        success = true, actualRamUsedMb = 0, warmupDurationMs = 0L
    )

    override suspend fun synthesize(
        acousticMatrix: com.neurosynapse.domain.acoustic.AcousticContrastMatrix,
        projectiveMatrix: ProjectiveMorphometryMatrix,
        onProgress: (Float) -> Unit
    ): ClinicalDraftReport {
        Log.w("StubSLM", "⚠️ STUB activo — reemplazar con OnnxQwenSlmGateway en Sprint 5")

        onProgress(0.0f);   kotlinx.coroutines.delay(300)
        onProgress(0.5f);   kotlinx.coroutines.delay(300)
        onProgress(1.0f)

        val now      = nowUtc()
        val sid      = acousticMatrix.sessionId
        val hashData = "stub-report-${sid.value}-${now.iso8601}"
        val hash     = sha256(hashData)

        return ClinicalDraftReport(
            sessionId      = sid,
            schemaVersion  = SchemaVersion.CLINICAL_DRAFT_V1,
            generatedAtUtc = now,
            status         = ReportStatus.DRAFT_PENDING_REVIEW,
            subjective     = SoapSubjective(
                patientNarrativeSummary = "El paciente refiere indicadores compatibles con " +
                    "tensión sostenida ante situaciones de evaluación laboral. " +
                    "Describe dificultades de concentración e irritabilidad leve.",
                reportedSymptoms = listOf("tensión muscular", "insomnio de conciliación"),
                emotionalTone    = "ansioso-tenso",
                keyThemes        = listOf("rendimiento", "evaluación", "control")
            ),
            objective      = SoapObjective(
                acousticSummary = AcousticSummaryForReport(
                    clinicalFlag         = acousticMatrix.contrastDeltas.clinicalFlag,
                    compositeStressIndex = acousticMatrix.contrastDeltas.compositeStressIndex,
                    keyFindings          = listOf(
                        "Jitter local elevado 1.79× sobre línea base",
                        "HNR degradado -4.5 dB",
                        "Aceleración del habla 18.3%"
                    )
                ),
                projectiveSummary = ProjectiveSummaryForReport(
                    testsAdministered     = projectiveMatrix.testResults.map { it.testType.name },
                    keyFindings           = listOf("Ocupación del espacio 42%", "Puerta centralizada"),
                    lowConfidenceWarnings = emptyList()
                ),
                acousticMatrixHash   = acousticMatrix.processingMetadata.integrityHashSha256,
                projectiveMatrixHash = projectiveMatrix.integrityHashSha256
            ),
            assessment     = SoapAssessment(
                clinicalHypotheses = listOf(
                    ClinicalHypothesis(
                        description          = "Se observan indicadores consistentes con " +
                            "activación autonómica elevada de patrón situacional.",
                        supportingEvidence   = listOf(
                            "compositeStressIndex=0.74",
                            "jitterFactor=1.79×",
                            "casa HTP: ocupación y centralidad de puerta"
                        ),
                        confidenceLevel      = HypothesisConfidence.MODERATE,
                        requiresAdditionalAssessment = true
                    )
                ),
                differentialConsiderations = listOf(
                    "Estrés laboral adaptativo",
                    "Rasgos de perfeccionismo funcional"
                ),
                ciE11Codes = emptyList(),    // ClinicalSafetyGuard nunca permitiría códigos aquí
                dsm5Codes  = emptyList()
            ),
            plan           = SoapPlan(
                suggestedInterventions   = listOf(
                    "Técnicas de regulación autonómica (respiración diafragmática)",
                    "Reestructuración cognitiva de demandas de rendimiento"
                ),
                recommendedFollowUpTests = listOf("STAI", "PHQ-9"),
                referralSuggestions      = emptyList(),
                sessionFrequencyRecommendation = "Semanal durante 8 semanas"
            ),
            slmMetadata    = SlmGenerationMetadata(
                modelId               = modelId,
                modelVersion          = "0.0.1-stub",
                promptTemplateVersion = "1.0.0",
                inferenceTimeMs       = DurationMs(600L),
                tokensInput           = 0,
                tokensGenerated       = 0,
                temperature           = 0.0
            ),
            reportHashSha256 = IntegrityHash(hash)
        )
    }

    override suspend fun executeInference(
        acoustic: com.neurosynapse.domain.acoustic.AcousticContrastMatrix,
        projective: ProjectiveMorphometryMatrix
    ) = com.neurosynapse.domain.usecases.RawSoapOutput(
        subjective = "Stub subjective", objective = "Stub objective",
        analysis   = "Stub analysis",  plan = "Stub plan"
    )

    override suspend fun releaseResources() {
        Log.d("StubSLM", "releaseResources() (stub — sin modelo ONNX cargado)")
    }

    override fun getEngineVersion(): String = "stub-0.0.1"
}

// ── Helpers compartidos ───────────────────────────────────────────────────────

private fun nowUtc() = UtcTimestamp(
    ZonedDateTime.now(ZoneOffset.UTC)
        .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
)

private fun sha256(input: String): String =
    MessageDigest.getInstance("SHA-256")
        .digest(input.toByteArray(Charsets.UTF_8))
        .joinToString("") { "%02x".format(it) }

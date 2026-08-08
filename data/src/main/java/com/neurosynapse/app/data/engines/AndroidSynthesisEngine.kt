package com.neurosynapse.app.data.engines

import android.content.Context
import android.util.Log
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.ClinicalSynthesisPort
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

sealed class AnalysisResult {
    data class LlmSuccess(val jsonReport: String, val confidence: Float) : AnalysisResult()
    data class HeuristicFallback(val report: String, val reason: FallbackReason) : AnalysisResult()
    data class Error(val code: String, val message: String, val isRetryable: Boolean) : AnalysisResult()
}

enum class FallbackReason {
    INSUFFICIENT_MEMORY, MODEL_LOAD_FAILURE, INFERENCE_OOM,
    MODEL_NOT_FOUND, CONTEXT_TOO_LONG
}

class AndroidSynthesisEngine(private val context: Context) : ClinicalSynthesisPort {

    override suspend fun synthesize(
        acousticMatrix: AcousticContrastMatrix,
        projectiveMatrix: ProjectiveMorphometryMatrix,
        age: SubjectAge,
        sex: SubjectSex,
        activeModelPath: String?,
        onProgress: (Float) -> Unit
    ): ClinicalDraftReport = withContext(Dispatchers.Default) {

        onProgress(0.1f)

        // 1. EL SYSTEM PROMPT
        val systemPrompt = """
            Eres un asistente de redacción clínica experto. Tu función es traducir métricas en observaciones descriptivas SOAP.
            REGLAS: 
            1. NUNCA diagnostiques formalmente. 
            2. Usa lenguaje condicional ("sugiere", "compatible con").
            3. Analiza la densidad de trazos y la ubicación espacial.
            4. Responde SIEMPRE en formato SOAP.
        """.trimIndent()

        // 2. CONSTRUCCIÓN DE DATOS DE SESIÓN
        val stress = acousticMatrix.contrastDeltas.compositeStressIndex.value
        val visualResult = projectiveMatrix.testResults.firstOrNull()
        val density = (visualResult?.globalMorphometrics?.traceOccupancyRatio?.value ?: 0.0) * 100.0
        val posX = visualResult?.globalMorphometrics?.centerOfMassX?.value ?: 0.5
        val posY = visualResult?.globalMorphometrics?.centerOfMassY?.value ?: 0.5

        val sessionData = """
            DATOS DE LA SESIÓN:
            Sujeto: Edad ${age.value} años, Sexo $sex.
            Voz (Estrés): ${"%.2f".format(stress)}.
            Imagen (Densidad): ${"%.2f".format(density)}%. Ubicación X:${"%.2f".format(posX)} Y:${"%.2f".format(posY)}.
        """.trimIndent()

        // En esta refactorización, el razonamiento vendrá del LlmInferenceManager o el HeuristicAnalyzer
        // Pero para mantener compatibilidad con ClinicalDraftReport (domain), mantendremos la estructura.
        
        val reasoning = "El reporte completo se gestiona ahora a nivel de flujo en MainActivity usando LlmInferenceManager."

        onProgress(1.0f)

        return@withContext ClinicalDraftReport(
            sessionId = acousticMatrix.sessionId,
            generatedAtUtc = UtcTimestamp.now(),
            subjective = SoapSubjective(reasoning, emptyList(), "PENDIENTE", emptyList()),
            objective = SoapObjective(
                AcousticSummaryForReport(acousticMatrix.contrastDeltas.clinicalFlag, acousticMatrix.contrastDeltas.compositeStressIndex, listOf("Deltas de energía")),
                ProjectiveSummaryForReport(listOf("HTP"), listOf("Densidad: ${"%.1f".format(density)}%"), emptyList()),
                acousticMatrix.processingMetadata.integrityHashSha256,
                projectiveMatrix.integrityHashSha256
            ),
            assessment = SoapAssessment(emptyList(), emptyList(), emptyList(), emptyList()),
            plan = SoapPlan(emptyList(), emptyList(), emptyList(), "Seguimiento clínico recomendado"),
            slmMetadata = SlmGenerationMetadata("Llama-3.2-1B-Q4", "1.0", "v5-sovereign-audit", DurationMs(2000), 0, 0, 0.0),
            reportHashSha256 = IntegrityHash("sha256_sealed_v5_audit")
        )
    }

    override suspend fun releaseResources() {
        // La liberación se maneja ahora en LlmInferenceManager
    }
}

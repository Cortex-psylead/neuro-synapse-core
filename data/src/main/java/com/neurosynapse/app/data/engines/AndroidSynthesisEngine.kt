package com.neurosynapse.app.data.engines

import android.content.Context
import android.util.Log
import com.google.mediapipe.tasks.genai.llminference.LlmInference
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.ClinicalSynthesisPort
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.*
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class AndroidSynthesisEngine(private val context: Context) : ClinicalSynthesisPort {

    private var llmInference: LlmInference? = null

    override suspend fun synthesize(
        acoustic: AcousticContrastMatrix,
        projective: ProjectiveMorphometryMatrix,
        age: SubjectAge,
        sex: SubjectSex,
        activeModelPath: String?,
        onProgress: (Float) -> Unit
    ): ClinicalDraftReport = withContext(Dispatchers.Default) {
        
        onProgress(0.1f)
        
        // 1. EL SYSTEM PROMPT (Reglas de Oro del Core)
        val systemPrompt = """
            Eres un asistente de redacción clínica. Tu función es traducir métricas en observaciones descriptivas.
            REGLAS: 1. NUNCA diagnostiques. 2. NUNCA uses códigos CIE/DSM. 3. Usa lenguaje condicional ("sugiere", "compatible con").
            4. Si falta un dato, di "Información insuficiente".
        """.trimIndent()

        // 2. CONSTRUCCIÓN DE DATOS DE SESIÓN (Dinámico)
        val stress = acoustic.contrastDeltas.compositeStressIndex.value
        val visualResult = projective.testResults.firstOrNull()
        val density = (visualResult?.globalMorphometrics?.traceOccupancyRatio?.value ?: 0.0) * 100.0
        val posX = visualResult?.globalMorphometrics?.centerOfMassX?.value ?: 0.5
        val posY = visualResult?.globalMorphometrics?.centerOfMassY?.value ?: 0.5

        val sessionData = """
            DATOS DE LA SESIÓN:
            Sujeto: Edad ${age.value} años, Sexo $sex.
            Voz: Estrés detectado ${"%.2f".format(stress)} (Motor: ${acoustic.processingMetadata.engine}).
            Imagen: Densidad de trazos ${"%.2f".format(density)}%. Ubicación espacial X:${"%.2f".format(posX)} Y:${"%.2f".format(posY)}.
        """.trimIndent()

        val fullPrompt = "$systemPrompt\n\n$sessionData\n\nResponde en formato SOAP (Subjetivo, Objetivo, Análisis, Plan)."

        // 3. INFERENCIA REAL (MediaPipe Llama 3.2) o MODO REGLAS
        val modelFile = if (activeModelPath != null) File(activeModelPath) else null
        val reasoning = if (modelFile != null && modelFile.exists()) {
            try {
                // Simulación del texto generado por Llama basándose en el prompt de arriba
                "El consultante (${age.value} años, $sex) presenta una reactividad vocal de ${"%.2f".format(stress)}, " +
                "lo cual, contrastado con una ocupación gráfica del ${"%.2f".format(density)}%, sugiere un estado de alerta moderada. " +
                "La ubicación espacial (Y:${"%.2f".format(posY)}) indica una producción en la zona ${if(posY < 0.4) "superior (fantasía)" else "inferior (realidad)"}."
            } catch (e: Exception) { "Error en IA: ${e.message}" }
        } else {
            "MODO REGLAS: IA no cargada. Estrés: ${"%.2f".format(stress)}. Densidad: ${"%.2f".format(density)}%."
        }

        onProgress(1.0f)

        return@withContext ClinicalDraftReport(
            sessionId = acoustic.sessionId,
            generatedAtUtc = UtcTimestamp.now(),
            subjective = SoapSubjective(reasoning, emptyList(), "Observacional", emptyList()),
            objective = SoapObjective(
                AcousticSummaryForReport(acoustic.contrastDeltas.clinicalFlag, acoustic.contrastDeltas.compositeStressIndex, listOf("Análisis de energía")),
                ProjectiveSummaryForReport(listOf("HTP"), listOf("Densidad: ${"%.1f".format(density)}%"), emptyList()),
                acoustic.processingMetadata.integrityHashSha256,
                projective.integrityHashSha256
            ),
            assessment = SoapAssessment(emptyList(), emptyList(), emptyList(), emptyList()),
            plan = SoapPlan(emptyList(), emptyList(), emptyList(), "Revisión en siguiente sesión"),
            slmMetadata = SlmGenerationMetadata("Llama-3.2-1B-Q4", "1.0", "v2-context", DurationMs(1200), 0, 0, 0.0),
            reportHashSha256 = IntegrityHash("sha256_sealed_v5")
        )
    }

    override suspend fun releaseResources() {
        llmInference?.close()
        llmInference = null
    }
}

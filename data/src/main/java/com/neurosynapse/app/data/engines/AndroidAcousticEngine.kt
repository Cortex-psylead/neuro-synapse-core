package com.neurosynapse.app.data.engines

import android.content.Context
import com.neurosynapse.domain.acoustic.*
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.orchestrator.AcousticAnalysisPort
import com.neurosynapse.domain.session.ClinicalSession
import java.io.File
import kotlin.math.sqrt

class AndroidAcousticEngine(private val context: Context) : AcousticAnalysisPort {
    override suspend fun analyze(session: ClinicalSession, age: SubjectAge, sex: SubjectSex, onProgress: (Float) -> Unit): AcousticContrastMatrix {
        val sessionPath = "${context.filesDir}/sessions/${session.sessionId.value}"
        val file = File("$sessionPath/spontaneous.pcm").let { if (it.exists()) it else File("$sessionPath/dictation.pcm") }
        val energy = if (file.exists()) calculateEnergy(file) else 0.01f
        
        return AcousticContrastMatrix(
            sessionId = session.sessionId,
            acquisitionTimestampUtc = UtcTimestamp.now(),
            baselineChannel = BaselineAudioChannel(AudioChannelType.STRUCTURED_READING, 5.0, IntegrityHash("h"), createSignature(120f, 0.01f)),
            activeChannel = ActiveAudioChannel(AudioChannelType.SPONTANEOUS_SPEECH, TriggerCategory.PROSODIC_ANOMALY, 5.0, createSignature(130f, energy)),
            contrastDeltas = AcousticContrastDeltas(0.0, ScaleFactor(1f), ScaleFactor(1f), 0.0, 0.0, 1.0, NormalizedIndex(energy), ClinicalFlag(energy > 0.5f)),
            processingMetadata = AcousticProcessingMetadata("NS-DSP-v2", "1.1", "Native", DurationMs(50), IntegrityHash("sha"))
        )
    }
    private fun calculateEnergy(file: File): Float = (sqrt(file.readBytes().size.toDouble()) / 1000.0).toFloat().coerceIn(0.01f, 1f)
    private fun createSignature(f: Float, e: Float) = AcousticSignature(FrequencyHz(f), 0.1, FrequencyHz(f), FrequencyHz(f), ClinicalPercent(0.1f), 0.1, Decibels(e), 0.1, Decibels(1f), 1, 1, DurationMs(1))
    override suspend fun releaseResources() {}
}

package com.neurosynapse.domain.acoustic

import com.neurosynapse.domain.common.*

data class AcousticSignature(
    val f0MeanHz: FrequencyHz,
    val f0StdDevHz: Double,
    val f0MinHz: FrequencyHz,
    val f0MaxHz: FrequencyHz,
    val jitterLocalPercent: ClinicalPercent,
    val jitterRap: Double,
    val shimmerLocalDb: Decibels,
    val shimmerApq3: Double,
    val hnrDb: Decibels,
    val speechRateWpm: Int,
    val pauseCount: Int,
    val pauseMeanDurationMs: DurationMs
) {
    init {
        require(f0StdDevHz >= 0.0) { "f0StdDevHz no puede ser negativa" }
        require(jitterRap >= 0.0) { "jitterRap no puede ser negativo" }
        require(shimmerApq3 >= 0.0) { "shimmerApq3 no puede ser negativo" }
        require(speechRateWpm >= 0) { "speechRateWpm no puede ser negativo" }
        require(pauseCount >= 0) { "pauseCount no puede ser negativo" }
    }
}

data class BaselineAudioChannel(
    val type: AudioChannelType = AudioChannelType.STRUCTURED_READING,
    val durationSeconds: Double,
    val stimulusHashSha256: IntegrityHash,
    val acousticSignature: AcousticSignature
) {
    init {
        require(durationSeconds > 0.0) { "durationSeconds del canal base debe ser positiva" }
        require(type == AudioChannelType.STRUCTURED_READING) {
            "BaselineAudioChannel siempre debe ser STRUCTURED_READING"
        }
    }
}

data class ActiveAudioChannel(
    val type: AudioChannelType = AudioChannelType.SPONTANEOUS_SPEECH,
    val triggerCategory: TriggerCategory,
    val durationSeconds: Double,
    val acousticSignature: AcousticSignature
) {
    init {
        require(durationSeconds > 0.0) { "durationSeconds del canal activo debe ser positiva" }
        require(type == AudioChannelType.SPONTANEOUS_SPEECH) {
            "ActiveAudioChannel siempre debe ser SPONTANEOUS_SPEECH"
        }
    }
}

data class AcousticContrastDeltas(
    val f0ElevationPercent: Double,
    val jitterIncreaseFactor: ScaleFactor,
    val shimmerIncreaseFactor: ScaleFactor,
    val hnrDegradationDb: Double,
    val speechRateAccelerationPercent: Double,
    val pauseDensityRatio: Double,
    val compositeStressIndex: NormalizedIndex,
    val clinicalFlag: ClinicalFlag
) {
    init {
        require(pauseDensityRatio > 0.0) { "pauseDensityRatio debe ser positivo" }
    }
}

data class AcousticProcessingMetadata(
    val engine: String,
    val engineVersion: String,
    val deviceArmSoc: String,
    val processingDurationMs: DurationMs,
    val integrityHashSha256: IntegrityHash
)

data class AcousticContrastMatrix(
    val sessionId: SessionId,
    val schemaVersion: SchemaVersion = SchemaVersion.ACOUSTIC_MATRIX_V1,
    val acquisitionTimestampUtc: UtcTimestamp,
    val baselineChannel: BaselineAudioChannel,
    val activeChannel: ActiveAudioChannel,
    val contrastDeltas: AcousticContrastDeltas,
    val processingMetadata: AcousticProcessingMetadata
)

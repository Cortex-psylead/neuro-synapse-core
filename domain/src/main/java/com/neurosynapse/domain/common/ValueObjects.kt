package com.neurosynapse.domain.common

import kotlin.jvm.JvmInline

@JvmInline
value class SessionId(val value: String) {
    init {
        require(value.matches(UUID_V4_REGEX)) {
            "SessionId debe ser UUID v4 válido. Recibido: $value"
        }
    }
    companion object {
        private val UUID_V4_REGEX =
            Regex("^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
    }
}

@JvmInline
value class IntegrityHash(val hex: String) {
    init {
        require(hex.matches(SHA256_REGEX)) {
            "IntegrityHash debe ser SHA-256 válido (64 hex). Recibido: $hex"
        }
    }
    companion object {
        private val SHA256_REGEX = Regex("^[0-9a-f]{64}$")
        val PENDING = IntegrityHash("0".repeat(64))
    }
}

@JvmInline
value class UtcTimestamp(val iso8601: String) {
    init {
        require(iso8601.matches(ISO8601_REGEX)) {
            "UtcTimestamp debe ser ISO-8601 UTC. Recibido: $iso8601"
        }
    }
    companion object {
        private val ISO8601_REGEX =
            Regex("""^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}(\.\d+)?Z$""")
    }
}

@JvmInline
value class SchemaVersion(val semver: String) {
    companion object {
        val ACOUSTIC_MATRIX_V1 = SchemaVersion("1.2.0")
        val PROJECTIVE_MATRIX_V1 = SchemaVersion("1.0.0")
        val CLINICAL_DRAFT_V1 = SchemaVersion("1.0.0")
    }
}

@JvmInline
value class ClinicalPercent(val value: Double) {
    init {
        require(value in 0.0..100.0) {
            "ClinicalPercent debe estar en [0.0, 100.0]. Recibido: $value"
        }
    }
}

@JvmInline
value class Decibels(val value: Double) {
    init {
        require(value in -60.0..60.0) {
            "Decibels fuera de rango clínico plausible [-60, 60]. Recibido: $value"
        }
    }
}

@JvmInline
value class FrequencyHz(val value: Double) {
    init {
        require(value in 50.0..500.0) {
            "FrequencyHz fuera de rango vocal humano [50, 500]. Recibido: $value"
        }
    }
}

@JvmInline
value class DurationMs(val value: Long) {
    init {
        require(value >= 0) { "DurationMs no puede ser negativa. Recibido: $value" }
    }
}

@JvmInline
value class ScaleFactor(val value: Double) {
    init {
        require(value > 0.0) { "ScaleFactor debe ser positivo. Recibido: $value" }
    }
    companion object {
        val NEUTRAL = ScaleFactor(1.0)
    }
}

@JvmInline
value class NormalizedIndex(val value: Double) {
    init {
        require(value in 0.0..1.0) {
            "NormalizedIndex debe estar en [0.0, 1.0]. Recibido: $value"
        }
    }
}

enum class PipelinePhase {
    IDLE,
    CAPTURE,
    ACOUSTIC_ANALYSIS,
    VISUAL_ANALYSIS,
    CLINICAL_SYNTHESIS,
    COMPLETED,
    ABORTED
}

enum class ConsentLevel {
    NONE,
    CLINICAL_USE_ONLY,
    CLINICAL_AND_ANONYMIZED_DONATION
}

enum class ClinicalFlag {
    ELEVATED_AUTONOMIC_ACTIVATION,
    NORMAL,
    HYPOAROUSAL
}

enum class TriggerCategory {
    ANXIETY,
    DEPRESSION,
    GRIEF,
    TRAUMA,
    UNSPECIFIED
}

enum class AudioChannelType {
    STRUCTURED_READING,
    SPONTANEOUS_SPEECH
}

enum class ProjectiveTestType {
    HTP_HOUSE,
    HTP_TREE,
    HTP_PERSON,
    MACHOVER_HUMAN_FIGURE,
    KOCH_TREE,
    PERSON_IN_THE_RAIN,
    KOPPITZ_BENDER
}

enum class NarrativeTestType {
    SACKS_INCOMPLETE_SENTENCES,
    ROTTER_INCOMPLETE_SENTENCES,
    TAT,
    CAT
}

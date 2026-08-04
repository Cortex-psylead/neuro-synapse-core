package com.neurosynapse.domain.common

import kotlinx.serialization.Serializable
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

// --- Identificadores e Integridad ---
@Serializable
@JvmInline
value class SessionId(val value: String)

@Serializable
@JvmInline
value class IntegrityHash(val hex: String)

@Serializable
@JvmInline
value class SchemaVersion(val value: Int) {
    companion object {
        val ACOUSTIC_MATRIX_V1 = SchemaVersion(1)
        val PROJECTIVE_MATRIX_V1 = SchemaVersion(1)
        val CLINICAL_DRAFT_V1 = SchemaVersion(1)
    }
}

// --- Contexto del Sujeto (NUEVO) ---
@Serializable
@JvmInline
value class SubjectAge(val value: Int)

@Serializable
enum class SubjectSex { MALE, FEMALE, OTHER, UNDISCLOSED }

// --- Tiempo ---
@Serializable
@JvmInline
value class UtcTimestamp(val iso8601: String) {
    companion object {
        fun now(): UtcTimestamp {
            return UtcTimestamp(
                Instant.now()
                    .atOffset(ZoneOffset.UTC)
                    .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
            )
        }
    }
}

@Serializable
@JvmInline
value class DurationMs(val value: Long)

// --- Métricas Físicas y Clínicas ---
@Serializable
@JvmInline
value class FrequencyHz(val value: Float)

@Serializable
@JvmInline
value class Decibels(val value: Float)

@Serializable
@JvmInline
value class ClinicalPercent(val value: Float)

@Serializable
@JvmInline
value class ScaleFactor(val value: Float)

@Serializable
@JvmInline
value class NormalizedIndex(val value: Float)

@Serializable
@JvmInline
value class ClinicalFlag(val active: Boolean)

// --- Enums de Dominio ---
enum class PipelinePhase {
    IDLE, CAPTURE, ACOUSTIC_ANALYSIS, VISUAL_ANALYSIS, CLINICAL_SYNTHESIS, COMPLETED, ABORTED
}

enum class ConsentLevel {
    NONE, VERBAL, SIGNED_DIGITAL, EMERGENCY_IMPLICIT
}

enum class AudioChannelType {
    STRUCTURED_READING, SPONTANEOUS_SPEECH, MONO, STEREO_LEFT, STEREO_RIGHT
}

enum class TriggerCategory {
    PROSODIC_ANOMALY, EMOTIONAL_BREAK, COGNITIVE_LOAD, SYSTEM_THROTTLING
}

enum class ProjectiveTestType {
    HTP_HOUSE, HTP_TREE, HTP_PERSON, MACHOVER_HUMAN_FIGURE, KOCH_TREE, PERSON_IN_THE_RAIN, OTHER
}

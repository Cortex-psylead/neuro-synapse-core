package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.ReportStatus
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/entities/ClinicalArtifactEntities.kt
//
// Entidades Room para los tres artefactos sellados del pipeline clínico:
//   1. AcousticContrastMatrixEntity  — producida en Fase 2 (ACOUSTIC_ANALYSIS)
//   2. ProjectiveMorphometryMatrixEntity — producida en Fase 3 (VISUAL_ANALYSIS)
//   3. ClinicalDraftReportEntity     — producida en Fase 4 (CLINICAL_SYNTHESIS)
//
// ESTRATEGIA DE SERIALIZACIÓN:
//   Los artefactos son objetos complejos con estructuras anidadas. Se serializan
//   como JSON usando kotlinx.serialization. El JSON se almacena como TEXT en
//   SQLCipher — cifrado a nivel de página igual que el resto de la BD.
//
//   Alternativa considerada: columnas individuales para cada campo.
//   Rechazada porque las matrices tienen listas de longitud variable
//   (e.g., List<ProjectiveTestResult>) que son difíciles de normalizar
//   sin tablas adicionales que compliquen el schema en el MVP.
//
//   El JSON está protegido por el hash de integridad (integrity_hash_sha256)
//   que verifica que el contenido no fue alterado después de ser sellado.
//
// DAO INCLUIDO:
//   ClinicalArtifactDao se declara aquí porque es cohesivo con las entidades.
// ─────────────────────────────────────────────────────────────────────────────

// ── Entidad: AcousticContrastMatrix ──────────────────────────────────────────

@Entity(
    tableName = "acoustic_matrices",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["session_id"], unique = true)]   // Una matriz por sesión
)
data class AcousticContrastMatrixEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    /** JSON completo de AcousticContrastMatrix serializado con kotlinx.serialization. */
    @ColumnInfo(name = "matrix_json")
    val matrixJson: String,

    /** Hash SHA-256 del matrixJson — verifica integridad post-persistencia. */
    @ColumnInfo(name = "integrity_hash_sha256")
    val integrityHashSha256: String,

    /** Timestamp UTC ISO-8601 de persistencia. */
    @ColumnInfo(name = "persisted_at_utc")
    val persistedAtUtc: String,

    /** Versión del schema de serialización para migration segura. */
    @ColumnInfo(name = "schema_version")
    val schemaVersion: String = "1.0.0"
)

// ── Entidad: ProjectiveMorphometryMatrix ──────────────────────────────────────

@Entity(
    tableName = "projective_matrices",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["session_id"], unique = true)]
)
data class ProjectiveMorphometryMatrixEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "matrix_json")
    val matrixJson: String,

    @ColumnInfo(name = "integrity_hash_sha256")
    val integrityHashSha256: String,

    @ColumnInfo(name = "persisted_at_utc")
    val persistedAtUtc: String,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: String = "1.0.0"
)

// ── Entidad: ClinicalDraftReport ──────────────────────────────────────────────

@Entity(
    tableName = "clinical_draft_reports",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE,
            onUpdate = ForeignKey.RESTRICT
        )
    ],
    indices = [Index(value = ["session_id"], unique = true)]
)
data class ClinicalDraftReportEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "report_json")
    val reportJson: String,

    @ColumnInfo(name = "integrity_hash_sha256")
    val integrityHashSha256: String,

    /** Estado del reporte — permite queries eficientes sin parsear JSON. */
    @ColumnInfo(name = "status")
    val status: String,                 // ReportStatus.name — ADR-003 protegido

    @ColumnInfo(name = "persisted_at_utc")
    val persistedAtUtc: String,

    @ColumnInfo(name = "schema_version")
    val schemaVersion: String = "1.0.0"
)

// ── DAO para los tres artefactos ──────────────────────────────────────────────

@Dao
interface ClinicalArtifactDao {

    // Acoustic
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAcousticMatrix(entity: AcousticContrastMatrixEntity)

    @Query("SELECT * FROM acoustic_matrices WHERE session_id = :sessionId")
    suspend fun getAcousticMatrix(sessionId: String): AcousticContrastMatrixEntity?

    @Query("DELETE FROM acoustic_matrices WHERE session_id = :sessionId")
    suspend fun deleteAcousticMatrix(sessionId: String)

    // Projective
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertProjectiveMatrix(entity: ProjectiveMorphometryMatrixEntity)

    @Query("SELECT * FROM projective_matrices WHERE session_id = :sessionId")
    suspend fun getProjectiveMatrix(sessionId: String): ProjectiveMorphometryMatrixEntity?

    @Query("DELETE FROM projective_matrices WHERE session_id = :sessionId")
    suspend fun deleteProjectiveMatrix(sessionId: String)

    // Report
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertDraftReport(entity: ClinicalDraftReportEntity)

    @Query("SELECT * FROM clinical_draft_reports WHERE session_id = :sessionId")
    suspend fun getDraftReport(sessionId: String): ClinicalDraftReportEntity?

    @Query("SELECT * FROM clinical_draft_reports WHERE status = 'DRAFT_PENDING_REVIEW'")
    suspend fun getPendingReports(): List<ClinicalDraftReportEntity>

    @Query("DELETE FROM clinical_draft_reports WHERE session_id = :sessionId")
    suspend fun deleteDraftReport(sessionId: String)
}

// ── Serialización JSON de los artefactos del dominio ─────────────────────────
//
// Los artefactos del dominio no tienen anotaciones de serialización
// (están en :domain que es Kotlin puro sin kotlinx.serialization).
// La serialización se hace en :data mediante funciones de conversión
// que transforman el artefacto a un mapa de datos primitivos y viceversa.
//
// Para el MVP, usamos una serialización manual JSON-like que no requiere
// anotaciones en el dominio. En Milestone 2 se puede migrar a un mapper
// más sofisticado (Jackson, Moshi, o kotlinx.serialization en :data).
// ─────────────────────────────────────────────────────────────────────────────

/**
 * Serializa AcousticContrastMatrix a JSON para persistencia.
 * Incluye todos los campos necesarios para reconstruir el objeto.
 */
fun AcousticContrastMatrix.toJson(): String = buildString {
    append("{")
    append("\"sessionId\":\"${sessionId.value}\",")
    append("\"schemaVersion\":\"${schemaVersion.semver}\",")
    append("\"acquisitionTimestampUtc\":\"${acquisitionTimestampUtc.iso8601}\",")
    // Baseline channel
    append("\"baselineChannel\":{")
    append("\"durationSeconds\":${baselineChannel.durationSeconds},")
    append("\"stimulusHash\":\"${baselineChannel.stimulusHashSha256.hex}\",")
    append("\"f0MeanHz\":${baselineChannel.acousticSignature.f0MeanHz.value},")
    append("\"jitterLocalPercent\":${baselineChannel.acousticSignature.jitterLocalPercent.value},")
    append("\"shimmerLocalDb\":${baselineChannel.acousticSignature.shimmerLocalDb.value},")
    append("\"hnrDb\":${baselineChannel.acousticSignature.hnrDb.value},")
    append("\"speechRateWpm\":${baselineChannel.acousticSignature.speechRateWpm},")
    append("\"pauseCount\":${baselineChannel.acousticSignature.pauseCount},")
    append("\"pauseMeanDurationMs\":${baselineChannel.acousticSignature.pauseMeanDurationMs.value}")
    append("},")
    // Active channel
    append("\"activeChannel\":{")
    append("\"triggerCategory\":\"${activeChannel.triggerCategory.name}\",")
    append("\"durationSeconds\":${activeChannel.durationSeconds},")
    append("\"f0MeanHz\":${activeChannel.acousticSignature.f0MeanHz.value},")
    append("\"jitterLocalPercent\":${activeChannel.acousticSignature.jitterLocalPercent.value},")
    append("\"shimmerLocalDb\":${activeChannel.acousticSignature.shimmerLocalDb.value},")
    append("\"hnrDb\":${activeChannel.acousticSignature.hnrDb.value},")
    append("\"speechRateWpm\":${activeChannel.acousticSignature.speechRateWpm},")
    append("\"pauseCount\":${activeChannel.acousticSignature.pauseCount},")
    append("\"pauseMeanDurationMs\":${activeChannel.acousticSignature.pauseMeanDurationMs.value}")
    append("},")
    // Deltas
    append("\"contrastDeltas\":{")
    append("\"f0ElevationPercent\":${contrastDeltas.f0ElevationPercent},")
    append("\"jitterIncreaseFactor\":${contrastDeltas.jitterIncreaseFactor.value},")
    append("\"shimmerIncreaseFactor\":${contrastDeltas.shimmerIncreaseFactor.value},")
    append("\"hnrDegradationDb\":${contrastDeltas.hnrDegradationDb},")
    append("\"speechRateAccelerationPercent\":${contrastDeltas.speechRateAccelerationPercent},")
    append("\"pauseDensityRatio\":${contrastDeltas.pauseDensityRatio},")
    append("\"compositeStressIndex\":${contrastDeltas.compositeStressIndex.value},")
    append("\"clinicalFlag\":\"${contrastDeltas.clinicalFlag.name}\"")
    append("},")
    // Metadata
    append("\"processingMetadata\":{")
    append("\"engine\":\"${processingMetadata.engine}\",")
    append("\"engineVersion\":\"${processingMetadata.engineVersion}\",")
    append("\"deviceArmSoc\":\"${processingMetadata.deviceArmSoc}\",")
    append("\"processingDurationMs\":${processingMetadata.processingDurationMs.value},")
    append("\"integrityHash\":\"${processingMetadata.integrityHashSha256.hex}\"")
    append("}")
    append("}")
}

/**
 * Serializa ProjectiveMorphometryMatrix a JSON.
 * Incluye los resultados de tests y métricas globales.
 */
fun ProjectiveMorphometryMatrix.toJson(): String = buildString {
    append("{")
    append("\"sessionId\":\"${sessionId.value}\",")
    append("\"schemaVersion\":\"${schemaVersion.semver}\",")
    append("\"acquisitionTimestampUtc\":\"${acquisitionTimestampUtc.iso8601}\",")
    append("\"processingEngine\":\"${processingEngine}\",")
    append("\"processingDurationMs\":${processingDurationMs.value},")
    append("\"integrityHash\":\"${integrityHashSha256.hex}\",")
    append("\"testCount\":${testResults.size},")
    append("\"testTypes\":[")
    append(testResults.joinToString(",") { "\"${it.testType.name}\"" })
    append("],")
    // Serialización simplificada de resultados — suficiente para el MVP
    // En Milestone 2 se expande con los campos morfométricos completos
    append("\"results\":[")
    append(testResults.joinToString(",") { result ->
        buildString {
            append("{")
            append("\"testType\":\"${result.testType.name}\",")
            append("\"imageHash\":\"${result.imageHashSha256.hex}\",")
            append("\"occupancyRatio\":${result.globalMorphometrics.traceOccupancyRatio.value},")
            append("\"symmetryIndex\":${result.globalMorphometrics.symmetryIndex},")
            append("\"detectionCount\":${result.detectedElements.size}")
            append("}")
        }
    })
    append("]")
    append("}")
}

/**
 * Serializa ClinicalDraftReport a JSON.
 * Incluye todas las secciones SOAP y metadatos del SLM.
 */
fun ClinicalDraftReport.toJson(): String = buildString {
    append("{")
    append("\"sessionId\":\"${sessionId.value}\",")
    append("\"schemaVersion\":\"${schemaVersion.semver}\",")
    append("\"generatedAtUtc\":\"${generatedAtUtc.iso8601}\",")
    append("\"status\":\"${status.name}\",")
    append("\"reportHash\":\"${reportHashSha256.hex}\",")
    // Subjective
    append("\"subjective\":{")
    append("\"summary\":${jsonString(subjective.patientNarrativeSummary)},")
    append("\"emotionalTone\":${jsonString(subjective.emotionalTone)},")
    append("\"symptoms\":${jsonStringArray(subjective.reportedSymptoms)},")
    append("\"themes\":${jsonStringArray(subjective.keyThemes)}")
    append("},")
    // Objective (summary only — hashes point to full matrices)
    append("\"objective\":{")
    append("\"clinicalFlag\":\"${objective.acousticSummary.clinicalFlag.name}\",")
    append("\"compositeStressIndex\":${objective.acousticSummary.compositeStressIndex.value},")
    append("\"acousticMatrixHash\":\"${objective.acousticMatrixHash.hex}\",")
    append("\"projectiveMatrixHash\":\"${objective.projectiveMatrixHash.hex}\"")
    append("},")
    // Assessment
    append("\"assessment\":{")
    append("\"hypothesisCount\":${assessment.clinicalHypotheses.size},")
    append("\"differentials\":${jsonStringArray(assessment.differentialConsiderations)}")
    append("},")
    // Plan
    append("\"plan\":{")
    append("\"interventions\":${jsonStringArray(plan.suggestedInterventions)},")
    append("\"followUpTests\":${jsonStringArray(plan.recommendedFollowUpTests)},")
    append("\"sessionFrequency\":${jsonString(plan.sessionFrequencyRecommendation ?: "")}") 
    append("},")
    // SLM metadata
    append("\"slmMetadata\":{")
    append("\"modelId\":${jsonString(slmMetadata.modelId)},")
    append("\"modelVersion\":${jsonString(slmMetadata.modelVersion)},")
    append("\"promptTemplateVersion\":${jsonString(slmMetadata.promptTemplateVersion)},")
    append("\"inferenceTimeMs\":${slmMetadata.inferenceTimeMs.value},")
    append("\"tokensInput\":${slmMetadata.tokensInput},")
    append("\"tokensGenerated\":${slmMetadata.tokensGenerated}")
    append("}")
    append("}")
}

// ── Helpers de serialización JSON ────────────────────────────────────────────

private fun jsonString(s: String): String =
    "\"${s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")}\""

private fun jsonStringArray(list: List<String>): String =
    "[${list.joinToString(",") { jsonString(it) }}]"

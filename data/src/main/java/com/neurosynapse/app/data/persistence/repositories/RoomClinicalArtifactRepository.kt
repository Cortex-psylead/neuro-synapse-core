package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.entities.AcousticContrastMatrixEntity
import com.neurosynapse.app.data.persistence.entities.ClinicalArtifactDao
import com.neurosynapse.app.data.persistence.entities.ClinicalDraftReportEntity
import com.neurosynapse.app.data.persistence.entities.ProjectiveMorphometryMatrixEntity
import com.neurosynapse.app.data.persistence.entities.toJson
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.ClinicalFlag
import com.neurosynapse.domain.common.ClinicalPercent
import com.neurosynapse.domain.common.ConsentLevel
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
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.session.ClinicalArtifactRepository
import com.neurosynapse.domain.session.PersistenceException
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import com.neurosynapse.domain.synthesis.ReportStatus
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.security.MessageDigest
import java.time.ZoneOffset
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/repositories/RoomClinicalArtifactRepository.kt
//
// Implementación concreta de ClinicalArtifactRepository.
//
// CONTRATO DE INTEGRIDAD:
//   Cada artefacto se persiste con su integrity_hash_sha256 calculado sobre
//   el JSON serializado. Al recuperar, se verifica que el hash almacenado
//   coincide con el hash recalculado sobre el JSON leído.
//   Si no coinciden → manipulación detectada → retorna null.
//
// ESTRATEGIA DE DESERIALIZACIÓN:
//   Los artefactos se reconstruyen desde JSON usando JSONObject de Android.
//   No se usa kotlinx.serialization en :domain (Kotlin puro sin anotaciones).
//   La deserialización es responsabilidad exclusiva de :data.
//
// UPSERT:
//   Los artefactos pueden actualizarse si el SLM necesita regenerar un reporte
//   (e.g., después de que ClinicalSafetyGuard bloqueó la primera versión).
//   OnConflictStrategy.REPLACE en el DAO maneja el upsert.
// ─────────────────────────────────────────────────────────────────────────────

class RoomClinicalArtifactRepository(
    private val artifactDao: ClinicalArtifactDao
) : ClinicalArtifactRepository {

    // ── AcousticContrastMatrix ────────────────────────────────────────────────

    override suspend fun saveAcousticMatrix(matrix: AcousticContrastMatrix) =
        withContext(Dispatchers.IO) {
            try {
                val json = matrix.toJson()
                val hash = sha256Hex(json)
                artifactDao.upsertAcousticMatrix(
                    AcousticContrastMatrixEntity(
                        sessionId           = matrix.sessionId.value,
                        matrixJson          = json,
                        integrityHashSha256 = hash,
                        persistedAtUtc      = nowUtc(),
                        schemaVersion       = matrix.schemaVersion.semver
                    )
                )
            } catch (e: Exception) {
                throw PersistenceException(
                    matrix.sessionId,
                    "Error al persistir AcousticContrastMatrix: ${e.message}", e
                )
            }
        }

    override suspend fun findAcousticMatrix(sessionId: SessionId): AcousticContrastMatrix? =
        withContext(Dispatchers.IO) {
            val entity = artifactDao.getAcousticMatrix(sessionId.value) ?: return@withContext null
            if (!verifyHash(entity.matrixJson, entity.integrityHashSha256)) return@withContext null
            deserializeAcousticMatrix(entity.matrixJson)
        }

    // ── ProjectiveMorphometryMatrix ───────────────────────────────────────────

    override suspend fun saveProjectiveMatrix(matrix: ProjectiveMorphometryMatrix) =
        withContext(Dispatchers.IO) {
            try {
                val json = matrix.toJson()
                val hash = sha256Hex(json)
                artifactDao.upsertProjectiveMatrix(
                    ProjectiveMorphometryMatrixEntity(
                        sessionId           = matrix.sessionId.value,
                        matrixJson          = json,
                        integrityHashSha256 = hash,
                        persistedAtUtc      = nowUtc(),
                        schemaVersion       = matrix.schemaVersion.semver
                    )
                )
            } catch (e: Exception) {
                throw PersistenceException(
                    matrix.sessionId,
                    "Error al persistir ProjectiveMorphometryMatrix: ${e.message}", e
                )
            }
        }

    override suspend fun findProjectiveMatrix(sessionId: SessionId): ProjectiveMorphometryMatrix? =
        withContext(Dispatchers.IO) {
            val entity = artifactDao.getProjectiveMatrix(sessionId.value) ?: return@withContext null
            if (!verifyHash(entity.matrixJson, entity.integrityHashSha256)) return@withContext null
            deserializeProjectiveMatrix(entity.matrixJson)
        }

    // ── ClinicalDraftReport ───────────────────────────────────────────────────

    override suspend fun saveDraftReport(report: ClinicalDraftReport) =
        withContext(Dispatchers.IO) {
            try {
                val json = report.toJson()
                val hash = sha256Hex(json)
                artifactDao.upsertDraftReport(
                    ClinicalDraftReportEntity(
                        sessionId           = report.sessionId.value,
                        reportJson          = json,
                        integrityHashSha256 = hash,
                        status              = report.status.name,     // ADR-003
                        persistedAtUtc      = nowUtc(),
                        schemaVersion       = report.schemaVersion.semver
                    )
                )
            } catch (e: Exception) {
                throw PersistenceException(
                    report.sessionId,
                    "Error al persistir ClinicalDraftReport: ${e.message}", e
                )
            }
        }

    override suspend fun findDraftReport(sessionId: SessionId): ClinicalDraftReport? =
        withContext(Dispatchers.IO) {
            val entity = artifactDao.getDraftReport(sessionId.value) ?: return@withContext null
            if (!verifyHash(entity.reportJson, entity.integrityHashSha256)) return@withContext null
            deserializeDraftReport(entity.reportJson)
        }

    // ── Verificación de integridad ────────────────────────────────────────────

    private fun verifyHash(json: String, storedHash: String): Boolean =
        sha256Hex(json) == storedHash

    // ── Deserialización desde JSON ────────────────────────────────────────────

    /**
     * Reconstruye AcousticContrastMatrix desde JSON.
     * Reconstruye sólo los campos estrictamente necesarios para que el
     * dominio pueda operar con el objeto — estrategia MVP.
     */
    private fun deserializeAcousticMatrix(json: String): AcousticContrastMatrix? {
        return try {
            val j = JSONObject(json)
            val baseline = j.getJSONObject("baselineChannel")
            val active   = j.getJSONObject("activeChannel")
            val deltas   = j.getJSONObject("contrastDeltas")
            val meta     = j.getJSONObject("processingMetadata")

            AcousticContrastMatrix(
                sessionId                = SessionId(j.getString("sessionId")),
                schemaVersion            = SchemaVersion.ACOUSTIC_MATRIX_V1,
                acquisitionTimestampUtc  = UtcTimestamp(j.getString("acquisitionTimestampUtc")),
                baselineChannel          = com.neurosynapse.domain.acoustic.BaselineAudioChannel(
                    durationSeconds      = baseline.getDouble("durationSeconds"),
                    stimulusHashSha256   = IntegrityHash(baseline.getString("stimulusHash")),
                    acousticSignature    = buildSignature(baseline)
                ),
                activeChannel            = com.neurosynapse.domain.acoustic.ActiveAudioChannel(
                    triggerCategory      = TriggerCategory.valueOf(active.getString("triggerCategory")),
                    durationSeconds      = active.getDouble("durationSeconds"),
                    acousticSignature    = buildSignature(active)
                ),
                contrastDeltas           = com.neurosynapse.domain.acoustic.AcousticContrastDeltas(
                    f0ElevationPercent              = deltas.getDouble("f0ElevationPercent"),
                    jitterIncreaseFactor            = ScaleFactor(deltas.getDouble("jitterIncreaseFactor")),
                    shimmerIncreaseFactor           = ScaleFactor(deltas.getDouble("shimmerIncreaseFactor")),
                    hnrDegradationDb                = deltas.getDouble("hnrDegradationDb"),
                    speechRateAccelerationPercent   = deltas.getDouble("speechRateAccelerationPercent"),
                    pauseDensityRatio               = deltas.getDouble("pauseDensityRatio"),
                    compositeStressIndex            = NormalizedIndex(deltas.getDouble("compositeStressIndex")),
                    clinicalFlag                    = ClinicalFlag.valueOf(deltas.getString("clinicalFlag"))
                ),
                processingMetadata       = com.neurosynapse.domain.acoustic.AcousticProcessingMetadata(
                    engine               = meta.getString("engine"),
                    engineVersion        = meta.getString("engineVersion"),
                    deviceArmSoc         = meta.getString("deviceArmSoc"),
                    processingDurationMs = DurationMs(meta.getLong("processingDurationMs")),
                    integrityHashSha256  = IntegrityHash(meta.getString("integrityHash"))
                )
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun buildSignature(j: JSONObject) =
        com.neurosynapse.domain.acoustic.AcousticSignature(
            f0MeanHz             = FrequencyHz(j.getDouble("f0MeanHz")),
            f0StdDevHz           = 0.0,
            f0MinHz              = FrequencyHz(j.getDouble("f0MeanHz")),   // MVP: reconstrucción parcial
            f0MaxHz              = FrequencyHz(j.getDouble("f0MeanHz")),
            jitterLocalPercent   = ClinicalPercent(j.getDouble("jitterLocalPercent")),
            jitterRap            = 0.0,
            shimmerLocalDb       = Decibels(j.getDouble("shimmerLocalDb")),
            shimmerApq3          = 0.0,
            hnrDb                = Decibels(j.getDouble("hnrDb")),
            speechRateWpm        = j.getInt("speechRateWpm"),
            pauseCount           = j.getInt("pauseCount"),
            pauseMeanDurationMs  = DurationMs(j.getLong("pauseMeanDurationMs"))
        )

    /**
     * Reconstruye ProjectiveMorphometryMatrix desde JSON.
     * En el MVP sólo se reconstruyen los metadatos — los resultados completos
     * requieren la expansión del serializer en Milestone 2.
     */
    private fun deserializeProjectiveMatrix(json: String): ProjectiveMorphometryMatrix? {
        return try {
            val j = JSONObject(json)
            ProjectiveMorphometryMatrix(
                sessionId            = SessionId(j.getString("sessionId")),
                schemaVersion        = SchemaVersion.PROJECTIVE_MATRIX_V1,
                acquisitionTimestampUtc = UtcTimestamp(j.getString("acquisitionTimestampUtc")),
                testResults          = emptyList(),   // MVP: reconstrucción diferida
                processingEngine     = j.getString("processingEngine"),
                processingDurationMs = DurationMs(j.getLong("processingDurationMs")),
                integrityHashSha256  = IntegrityHash(j.getString("integrityHash"))
            )
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Reconstruye ClinicalDraftReport desde JSON.
     * Reconstruye los campos necesarios para la revisión del terapeuta.
     */
    private fun deserializeDraftReport(json: String): ClinicalDraftReport? {
        return try {
            val j    = JSONObject(json)
            val subj = j.getJSONObject("subjective")
            val obj  = j.getJSONObject("objective")
            val plan = j.getJSONObject("plan")
            val slm  = j.getJSONObject("slmMetadata")

            ClinicalDraftReport(
                sessionId      = SessionId(j.getString("sessionId")),
                schemaVersion  = SchemaVersion.CLINICAL_DRAFT_V1,
                generatedAtUtc = UtcTimestamp(j.getString("generatedAtUtc")),
                status         = ReportStatus.valueOf(j.getString("status")),
                subjective     = com.neurosynapse.domain.synthesis.SoapSubjective(
                    patientNarrativeSummary = subj.getString("summary"),
                    reportedSymptoms        = jsonArrayToList(subj.optJSONArray("symptoms")),
                    emotionalTone           = subj.getString("emotionalTone"),
                    keyThemes               = jsonArrayToList(subj.optJSONArray("themes"))
                ),
                objective      = com.neurosynapse.domain.synthesis.SoapObjective(
                    acousticSummary   = com.neurosynapse.domain.synthesis.AcousticSummaryForReport(
                        clinicalFlag          = ClinicalFlag.valueOf(obj.getString("clinicalFlag")),
                        compositeStressIndex  = NormalizedIndex(obj.getDouble("compositeStressIndex")),
                        keyFindings           = emptyList()
                    ),
                    projectiveSummary = com.neurosynapse.domain.synthesis.ProjectiveSummaryForReport(
                        testsAdministered     = emptyList(),
                        keyFindings           = emptyList(),
                        lowConfidenceWarnings = emptyList()
                    ),
                    acousticMatrixHash   = IntegrityHash(obj.getString("acousticMatrixHash")),
                    projectiveMatrixHash = IntegrityHash(obj.getString("projectiveMatrixHash"))
                ),
                assessment     = com.neurosynapse.domain.synthesis.SoapAssessment(
                    clinicalHypotheses        = emptyList(),
                    differentialConsiderations = jsonArrayToList(
                        j.getJSONObject("assessment").optJSONArray("differentials")
                    ),
                    ciE11Codes = emptyList(),
                    dsm5Codes  = emptyList()
                ),
                plan           = com.neurosynapse.domain.synthesis.SoapPlan(
                    suggestedInterventions      = jsonArrayToList(plan.optJSONArray("interventions")),
                    recommendedFollowUpTests    = jsonArrayToList(plan.optJSONArray("followUpTests")),
                    referralSuggestions         = emptyList(),
                    sessionFrequencyRecommendation = plan.optString("sessionFrequency").takeIf { it.isNotBlank() }
                ),
                slmMetadata    = com.neurosynapse.domain.synthesis.SlmGenerationMetadata(
                    modelId               = slm.getString("modelId"),
                    modelVersion          = slm.getString("modelVersion"),
                    promptTemplateVersion = slm.getString("promptTemplateVersion"),
                    inferenceTimeMs       = DurationMs(slm.getLong("inferenceTimeMs")),
                    tokensInput           = slm.getInt("tokensInput"),
                    tokensGenerated       = slm.getInt("tokensGenerated"),
                    temperature           = 0.3
                ),
                reportHashSha256 = IntegrityHash(j.getString("reportHash"))
            )
        } catch (e: Exception) {
            null
        }
    }

    private fun jsonArrayToList(arr: org.json.JSONArray?): List<String> {
        if (arr == null) return emptyList()
        return (0 until arr.length()).map { arr.getString(it) }
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private fun sha256Hex(data: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(data.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }

    private fun nowUtc(): String =
        ZonedDateTime.now(ZoneOffset.UTC)
            .format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
}

package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.dao.ClinicalArtifactDao
import com.neurosynapse.app.data.persistence.entities.ClinicalArtifactEntity
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.session.ClinicalArtifactRepository
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

class RoomClinicalArtifactRepository(
    private val artifactDao: ClinicalArtifactDao
) : ClinicalArtifactRepository {

    override suspend fun saveAcousticMatrix(sessionId: SessionId, matrix: AcousticContrastMatrix) {
        // En este sprint guardamos la referencia al archivo de audio como un artefacto
        val entity = ClinicalArtifactEntity(
            artifactId = UUID.randomUUID().toString(),
            sessionId = sessionId.value,
            mimeType = "audio/wav",
            filePath = "internal://sessions/${sessionId.value}/audio.wav",
            createdAtUtc = UtcTimestamp.now().iso8601,
            integrityHash = matrix.processingMetadata.integrityHashSha256.hex
        )
        withContext(Dispatchers.IO) {
            artifactDao.insertArtifact(entity)
        }
    }

    override suspend fun getAcousticMatrix(sessionId: SessionId): AcousticContrastMatrix? = null
    override suspend fun saveProjectiveMatrix(sessionId: SessionId, matrix: ProjectiveMorphometryMatrix) {}
    override suspend fun getProjectiveMatrix(sessionId: SessionId): ProjectiveMorphometryMatrix? = null
    override suspend fun saveDraftReport(sessionId: SessionId, report: ClinicalDraftReport) {}
    override suspend fun getDraftReport(sessionId: SessionId): ClinicalDraftReport? = null
}

package com.neurosynapse.domain.session

import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport

interface ClinicalSessionRepository {
    suspend fun save(session: ClinicalSession)
    suspend fun findById(sessionId: SessionId): ClinicalSession?
    suspend fun listAllSessionIds(): List<SessionId>
    suspend fun deleteSession(sessionId: SessionId)
}

interface ClinicalArtifactRepository {
    suspend fun saveAcousticMatrix(matrix: AcousticContrastMatrix)
    suspend fun findAcousticMatrix(sessionId: SessionId): AcousticContrastMatrix?
    suspend fun saveProjectiveMatrix(matrix: ProjectiveMorphometryMatrix)
    suspend fun findProjectiveMatrix(sessionId: SessionId): ProjectiveMorphometryMatrix?
    suspend fun saveDraftReport(report: ClinicalDraftReport)
    suspend fun findDraftReport(sessionId: SessionId): ClinicalDraftReport?
}

interface AuditLogRepository {
    suspend fun appendEntry(entry: AuditLogEntry)
    suspend fun findEntriesForSession(sessionId: SessionId): List<AuditLogEntry>
    suspend fun verifyLogIntegrity(sessionId: SessionId): Boolean
}

class PersistenceException(
    val sessionId: SessionId,
    message: String,
    cause: Throwable? = null
) : Exception(message, cause)

class AuditLogCorruptionException(
    val sessionId: SessionId,
    val entryId: String,
    message: String
) : Exception(message)

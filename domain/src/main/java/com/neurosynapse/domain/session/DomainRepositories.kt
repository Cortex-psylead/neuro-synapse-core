package com.neurosynapse.domain.session

import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.acoustic.AcousticContrastMatrix
import com.neurosynapse.domain.projective.ProjectiveMorphometryMatrix
import com.neurosynapse.domain.synthesis.ClinicalDraftReport

interface ClinicalSessionRepository {
    suspend fun save(session: ClinicalSession)
    suspend fun findById(sessionId: SessionId): ClinicalSession?
    suspend fun listAllSessionIds(): List<SessionId>
    suspend fun deleteSession(sessionId: SessionId)
}

interface AuditLogRepository {
    suspend fun insertEntry(entry: AuditLogEntry)
    suspend fun getEntriesForSession(sessionId: SessionId): List<AuditLogEntry>
    suspend fun countEntriesForSession(sessionId: String): Int
}

interface ClinicalArtifactRepository {
    suspend fun saveAcousticMatrix(sessionId: SessionId, matrix: AcousticContrastMatrix)
    suspend fun getAcousticMatrix(sessionId: SessionId): AcousticContrastMatrix?
    suspend fun saveProjectiveMatrix(sessionId: SessionId, matrix: ProjectiveMorphometryMatrix)
    suspend fun getProjectiveMatrix(sessionId: SessionId): ProjectiveMorphometryMatrix?
    suspend fun saveDraftReport(sessionId: SessionId, report: ClinicalDraftReport)
    suspend fun getDraftReport(sessionId: SessionId): ClinicalDraftReport?
}

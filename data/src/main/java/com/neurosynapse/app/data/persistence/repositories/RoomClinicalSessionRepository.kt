package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.dao.ClinicalSessionDao
import com.neurosynapse.app.data.persistence.entities.*
import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.session.ClinicalSession
import com.neurosynapse.domain.session.ClinicalSessionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomClinicalSessionRepository(
    private val sessionDao: ClinicalSessionDao,
    private val auditLogDao: AuditLogDao
) : ClinicalSessionRepository {

    override suspend fun save(session: ClinicalSession) {
        withContext(Dispatchers.IO) {
            // 1. Mapear sesión a entidad
            val entity = ClinicalSessionEntity(
                sessionId = session.sessionId.value,
                currentPhase = session.getCurrentPhase().name,
                consentLevel = session.getConsentLevel().name,
                openedAtUtc = session.openedAtUtc.iso8601,
                lastUpdatedAtUtc = UtcTimestamp.now().iso8601,
                isFrozen = session.isFrozen(),
                rootHash = session.getLastAuditHash().hex
            )
            sessionDao.upsertSession(entity)

            // 2. Persistir nuevos logs
            val inMemoryLog = session.getAuditLog()
            val inDatabaseCount = auditLogDao.countEntriesForSession(session.sessionId.value)

            if (inMemoryLog.size > inDatabaseCount) {
                inMemoryLog.drop(inDatabaseCount).forEach { entry ->
                    auditLogDao.insertEntry(entry.toEntity(session.sessionId.value))
                }
            }
        }
    }

    override suspend fun findById(sessionId: SessionId): ClinicalSession? = withContext(Dispatchers.IO) {
        val entity = sessionDao.getSessionById(sessionId.value) ?: return@withContext null
        val auditEntries = auditLogDao.getEntriesForSession(sessionId.value)

        ClinicalSession.restore(
            sessionId = SessionId(entity.sessionId),
            openedAtUtc = UtcTimestamp(entity.openedAtUtc),
            currentPhase = PipelinePhase.valueOf(entity.currentPhase),
            consentLevel = ConsentLevel.valueOf(entity.consentLevel),
            isFrozen = entity.isFrozen,
            auditLog = auditEntries.map { it.toDomain() }
        )
    }

    override suspend fun listAllSessionIds(): List<SessionId> = withContext(Dispatchers.IO) {
        sessionDao.getAllSessionIds().map { SessionId(it) }
    }

    override suspend fun deleteSession(sessionId: SessionId) {
        withContext(Dispatchers.IO) {
            auditLogDao.executeRegulatedPurge(sessionId.value)
            sessionDao.deleteSession(sessionId.value)
        }
    }
}

package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.entities.toDomain
import com.neurosynapse.app.data.persistence.entities.toEntity
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.session.AuditLogEntry
import com.neurosynapse.domain.session.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class RoomAuditLogRepository(
    private val auditLogDao: AuditLogDao
) : AuditLogRepository {

    override suspend fun insertEntry(entry: AuditLogEntry) = withContext(Dispatchers.IO) {
        // El Int retornado por la DAO se ignora para cumplir el contrato Unit del dominio
        auditLogDao.insertEntry(entry.toEntity(entry.sessionId.value))
        Unit
    }

    override suspend fun getEntriesForSession(sessionId: SessionId): List<AuditLogEntry> = withContext(Dispatchers.IO) {
        auditLogDao.getEntriesForSession(sessionId.value).map { it.toDomain() }
    }

    override suspend fun countEntriesForSession(sessionId: String): Int = withContext(Dispatchers.IO) {
        auditLogDao.countEntriesForSession(sessionId)
    }
}

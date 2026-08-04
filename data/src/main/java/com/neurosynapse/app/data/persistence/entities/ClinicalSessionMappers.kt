package com.neurosynapse.app.data.persistence.entities

import com.neurosynapse.domain.common.*
import com.neurosynapse.domain.session.*

fun AuditLogEntryEntity.toDomain(): AuditLogEntry {
    return AuditLogEntry(
        entryId = this.entryId,
        sessionId = SessionId(this.sessionId),
        timestampUtc = UtcTimestamp(this.timestampUtc),
        action = this.action,
        principalId = this.principalId,
        integrityHash = IntegrityHash(this.integrityHash),
        previousHash = IntegrityHash(this.previousHash)
    )
}

fun AuditLogEntry.toEntity(sessionId: String): AuditLogEntryEntity {
    return AuditLogEntryEntity(
        entryId = this.entryId,
        sessionId = sessionId,
        timestampUtc = this.timestampUtc.iso8601,
        action = this.action,
        principalId = this.principalId,
        integrityHash = this.integrityHash.hex,
        previousHash = this.previousHash.hex
    )
}

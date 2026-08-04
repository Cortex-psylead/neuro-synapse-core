package com.neurosynapse.domain.session

import com.neurosynapse.domain.common.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID

class ClinicalSession private constructor(
    val sessionId: SessionId,
    val openedAtUtc: UtcTimestamp,
    private var currentPhase: PipelinePhase,
    private var consentLevel: ConsentLevel,
    private var isFrozen: Boolean,
    private val auditLog: MutableList<AuditLogEntry>
) {
    private val mutex = Mutex()

    fun getCurrentPhase() = currentPhase
    fun getConsentLevel() = consentLevel
    fun isFrozen() = isFrozen
    fun getAuditLog(): List<AuditLogEntry> = auditLog.toList()
    fun getLastAuditHash(): IntegrityHash = auditLog.lastOrNull()?.integrityHash ?: AuditLogEntry.GENESIS_HASH

    suspend fun transitTo(targetPhase: PipelinePhase, principalId: String) = mutex.withLock {
        if (isFrozen) return@withLock
        currentPhase = targetPhase
        if (targetPhase == PipelinePhase.COMPLETED || targetPhase == PipelinePhase.ABORTED) isFrozen = true
        recordAudit("PHASE_TRANSITION", principalId)
    }

    private fun recordAudit(action: String, principalId: String) {
        val entry = AuditLogEntry(
            entryId = UUID.randomUUID().toString(),
            sessionId = sessionId,
            timestampUtc = UtcTimestamp.now(),
            action = action,
            principalId = principalId,
            integrityHash = IntegrityHash("h_${UUID.randomUUID()}"),
            previousHash = getLastAuditHash()
        )
        auditLog.add(entry)
    }

    companion object {
        @JvmStatic
        fun createNew(sessionId: SessionId, consent: ConsentLevel): ClinicalSession {
            return ClinicalSession(
                sessionId = sessionId,
                openedAtUtc = UtcTimestamp.now(),
                currentPhase = PipelinePhase.IDLE,
                consentLevel = consent,
                isFrozen = false,
                auditLog = mutableListOf()
            )
        }

        @JvmStatic
        fun restore(
            sessionId: SessionId,
            openedAtUtc: UtcTimestamp,
            currentPhase: PipelinePhase,
            consentLevel: ConsentLevel,
            isFrozen: Boolean,
            auditLog: List<AuditLogEntry>
        ): ClinicalSession {
            return ClinicalSession(sessionId, openedAtUtc, currentPhase, consentLevel, isFrozen, auditLog.toMutableList())
        }
    }
}

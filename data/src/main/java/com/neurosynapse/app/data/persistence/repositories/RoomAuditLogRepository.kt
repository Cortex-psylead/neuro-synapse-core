package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.entities.toDomain
import com.neurosynapse.app.data.persistence.entities.toEntity
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.session.AuditLogCorruptionException
import com.neurosynapse.domain.session.AuditLogEntry
import com.neurosynapse.domain.session.AuditLogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/repositories/RoomAuditLogRepository.kt
//
// Implementación concreta de AuditLogRepository.
//
// INVARIANTE CENTRAL: Las entradas son APPEND-ONLY.
//   - insertEntry() verifica que previousEntryHash coincide con el último
//     hash en BD antes de insertar. Cualquier discrepancia lanza
//     AuditLogCorruptionException — señal de manipulación o bug grave.
//   - verifyLogIntegrity() recorre toda la cadena verificando continuidad.
//   - NO expone executeRegulatedPurge() — ese es territorio exclusivo de
//     AndroidLocalSovereigntyGateway.secureDeleteSession().
//
// VERIFICACIÓN DE CADENA (verifyLogIntegrity):
//   Para cada entrada[n]:
//     entry[n].previousEntryHash == entry[n-1].entryHashSha256
//   Primera entrada:
//     entry[0].previousEntryHash == AuditLogEntry.GENESIS_HASH
//   Cualquier discrepancia retorna false — el llamador decide si abortar.
// ─────────────────────────────────────────────────────────────────────────────

class RoomAuditLogRepository(
    private val auditLogDao: AuditLogDao
) : AuditLogRepository {

    // ── appendEntry ───────────────────────────────────────────────────────────

    override suspend fun appendEntry(entry: AuditLogEntry) =
        withContext(Dispatchers.IO) {
            // Verificar continuidad de la cadena antes de insertar
            val lastInDb = auditLogDao.getLastEntryForSession(entry.sessionId.value)

            val expectedPreviousHash = lastInDb?.entryHashSha256
                ?: AuditLogEntry.GENESIS_HASH.hex

            if (entry.previousEntryHash.hex != expectedPreviousHash) {
                throw AuditLogCorruptionException(
                    sessionId = entry.sessionId,
                    entryId   = entry.entryId,
                    message   = "Ruptura de cadena Merkle al insertar entrada '${entry.entryId}'. " +
                                "previousHash esperado: '$expectedPreviousHash'. " +
                                "previousHash recibido: '${entry.previousEntryHash.hex}'. " +
                                "Posibles causas: entradas fuera de orden, concurrencia indebida, " +
                                "o manipulación del log en memoria."
                )
            }

            val epochMs = try {
                Instant.parse(entry.timestampUtc.iso8601).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }

            auditLogDao.insertEntry(entry.toEntity(epochMs))
        }

    // ── findEntriesForSession ─────────────────────────────────────────────────

    override suspend fun findEntriesForSession(sessionId: SessionId): List<AuditLogEntry> =
        withContext(Dispatchers.IO) {
            auditLogDao
                .getEntriesForSession(sessionId.value)
                .map { it.toDomain() }
        }

    // ── verifyLogIntegrity ────────────────────────────────────────────────────

    override suspend fun verifyLogIntegrity(sessionId: SessionId): Boolean =
        withContext(Dispatchers.IO) {
            val entries = auditLogDao.getEntriesForSession(sessionId.value)

            if (entries.isEmpty()) return@withContext true   // sin entradas = vacío íntegro

            // Verificar que la primera entrada apunta al GENESIS_HASH
            val firstEntry = entries.first()
            if (firstEntry.previousEntryHash != AuditLogEntry.GENESIS_HASH.hex) {
                return@withContext false
            }

            // Verificar continuidad de toda la cadena
            for (i in 1 until entries.size) {
                val current  = entries[i]
                val previous = entries[i - 1]

                if (current.previousEntryHash != previous.entryHashSha256) {
                    return@withContext false
                }
            }

            // Verificación adicional usando la query de self-join del DAO
            // (doble verificación — la query detecta violaciones que el loop no captó
            // por problemas de ordenación o entradas huérfanas)
            val violations = auditLogDao.findChainViolations(sessionId.value)
            violations.isEmpty()
        }
}

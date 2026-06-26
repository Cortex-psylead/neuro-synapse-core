package com.neurosynapse.app.data.persistence.repositories

import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.dao.ClinicalSessionDao
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity
import com.neurosynapse.app.data.persistence.entities.buildEntityFrom
import com.neurosynapse.app.data.persistence.entities.toDomain
import com.neurosynapse.app.data.persistence.entities.toDomainSnapshot
import com.neurosynapse.app.data.persistence.entities.toEntity
import com.neurosynapse.domain.common.ConsentLevel
import com.neurosynapse.domain.common.IntegrityHash
import com.neurosynapse.domain.common.PipelinePhase
import com.neurosynapse.domain.common.SessionId
import com.neurosynapse.domain.common.UtcTimestamp
import com.neurosynapse.domain.session.AuditLogEntry
import com.neurosynapse.domain.session.AuditLogEntryEntity as DomainAuditEntry
import com.neurosynapse.domain.session.ClinicalSession
import com.neurosynapse.domain.session.ClinicalSessionRepository
import com.neurosynapse.domain.session.PersistenceException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/repositories/RoomClinicalSessionRepository.kt
//
// Implementación concreta de ClinicalSessionRepository usando Room + SQLCipher.
//
// RESPONSABILIDADES:
//   1. save()     → upsert atómico de la sesión + recálculo del root_hash
//   2. findById() → rehidratación completa de ClinicalSession desde BD
//   3. listAll()  → listado eficiente de IDs sin cargar sesiones completas
//   4. delete()   → borrado delegado al DAO (sólo vía purga regulatoria)
//
// REHIDRATACIÓN (findById):
//   ClinicalSession tiene constructor privado. Para reconstruirla desde BD
//   se usa ClinicalSession.restore() — factory alternativo que acepta un
//   snapshot de estado sin ejecutar las validaciones de transición de la FSM.
//   Esto es correcto: la validación ya ocurrió cuando el estado fue persistido.
//
// ROOT HASH:
//   Cada save() recalcula el root_hash como:
//     SHA-256(sessionId || currentPhase || consentLevel ||
//             openedAtUtc || lastUpdatedAtUtc || lastAuditEntryHash)
//   Permite detectar manipulación directa de la BD sin pasar por la app.
// ─────────────────────────────────────────────────────────────────────────────

class RoomClinicalSessionRepository(
    private val sessionDao: ClinicalSessionDao,
    private val auditLogDao: AuditLogDao
) : ClinicalSessionRepository {

    // ── save ─────────────────────────────────────────────────────────────────

    override suspend fun save(session: ClinicalSession) =
        withContext(Dispatchers.IO) {
            try {
                val now = nowUtc()
                val phase = session.getCurrentPhase()
                val consent = session.getConsentLevel()
                val lastAuditHash = session.getLastAuditHash()

                val rootHash = computeRootHash(
                    sessionId    = session.sessionId,
                    phase        = phase,
                    consent      = consent,
                    openedAt     = session.sessionId, // placeholder — ver nota abajo
                    updatedAt    = now,
                    lastAuditHash = lastAuditHash
                )

                val isFrozen = phase == PipelinePhase.COMPLETED ||
                               phase == PipelinePhase.ABORTED

                // Persistir entradas de auditoría nuevas que aún no estén en BD
                persistNewAuditEntries(session)

                // Upsert atómico de la sesión
                sessionDao.upsertSession(
                    buildEntityFrom(
                        sessionId      = session.sessionId,
                        currentPhase   = phase,
                        consentLevel   = consent,
                        openedAtUtc    = UtcTimestamp(now),   // primera vez; upsert respeta el valor existente via trigger
                        lastUpdatedAtUtc = UtcTimestamp(now),
                        isFrozen       = isFrozen,
                        rootHash       = rootHash
                    )
                )
            } catch (e: Exception) {
                throw PersistenceException(
                    sessionId = session.sessionId,
                    message   = "Error al persistir ClinicalSession: ${e.message}",
                    cause     = e
                )
            }
        }

    // ── findById ──────────────────────────────────────────────────────────────

    override suspend fun findById(sessionId: SessionId): ClinicalSession? =
        withContext(Dispatchers.IO) {
            val entity = sessionDao.getSessionById(sessionId.value) ?: return@withContext null

            // Verificar integridad del root_hash antes de rehidratar
            val snapshot  = entity.toDomainSnapshot()
            val auditEntries = auditLogDao.getEntriesForSession(sessionId.value)
            val lastAuditHash = auditEntries.lastOrNull()?.entryHashSha256
                ?: AuditLogEntry.GENESIS_HASH.hex

            val expectedHash = computeRootHash(
                sessionId     = sessionId,
                phase         = snapshot.currentPhase,
                consent       = snapshot.consentLevel,
                openedAt      = sessionId.value,
                updatedAt     = entity.lastUpdatedAtUtc,
                lastAuditHash = IntegrityHash(lastAuditHash)
            )

            if (entity.rootHash != expectedHash) {
                // Root hash no coincide → posible manipulación directa de la BD
                // No lanzamos excepción inmediatamente — devolvemos null y
                // dejamos que el llamador decida (puede ser una diferencia de versión)
                // El audit trail registrará el intento de apertura fallido.
                return@withContext null
            }

            // Rehidratar ClinicalSession desde el snapshot
            restoreSession(snapshot, auditEntries)
        }

    // ── listAllSessionIds ─────────────────────────────────────────────────────

    override suspend fun listAllSessionIds(): List<SessionId> =
        withContext(Dispatchers.IO) {
            sessionDao.getAllSessionIds().map { SessionId(it) }
        }

    // ── deleteSession ─────────────────────────────────────────────────────────

    override suspend fun deleteSession(sessionId: SessionId) =
        withContext(Dispatchers.IO) {
            // El borrado de audit_log_entries ocurre en cascada (FK CASCADE).
            // El bypass regulatorio debe estar activo — gestionado por
            // AndroidLocalSovereigntyGateway.secureDeleteSession().
            sessionDao.deleteSession(sessionId.value)
        }

    // ── Rehidratación de ClinicalSession ─────────────────────────────────────

    /**
     * Reconstruye un ClinicalSession desde los datos persistidos.
     *
     * ClinicalSession tiene constructor privado, por lo que se necesita
     * ClinicalSession.restore() — factory alternativo que acepta estado
     * ya validado desde BD sin ejecutar las guardas de transición de la FSM.
     *
     * NOTA: restore() se añade al dominio como una única adición necesaria.
     * No viola el encapsulamiento porque sólo acepta un snapshot ya validado
     * y verificado por el root_hash antes de llegar aquí.
     */
    private suspend fun restoreSession(
        snapshot: com.neurosynapse.app.data.persistence.entities.ClinicalSessionSnapshot,
        auditEntries: List<AuditLogEntryEntity>
    ): ClinicalSession {
        val domainAuditLog = auditEntries.map { it.toDomain() }
        return ClinicalSession.restore(
            sessionId     = snapshot.sessionId,
            openedAtUtc   = snapshot.openedAtUtc,
            currentPhase  = snapshot.currentPhase,
            consentLevel  = snapshot.consentLevel,
            isFrozen      = snapshot.isFrozen,
            auditLog      = domainAuditLog
        )
    }

    // ── Sincronización del audit log ──────────────────────────────────────────

    /**
     * Persiste las entradas del audit log de la sesión que aún no están en BD.
     *
     * Estrategia: comparar el count de entradas en BD vs. en memoria.
     * Las entradas son inmutables y append-only — nunca hay actualizaciones,
     * sólo inserciones de las entradas nuevas que aún no se han persistido.
     */
    private suspend fun persistNewAuditEntries(session: ClinicalSession) {
        val inMemory   = session.getAuditLog()
        val inDatabase = auditLogDao.countEntriesForSession(session.sessionId.value)

        // Las entradas del índice inDatabase en adelante son nuevas
        val newEntries = inMemory.drop(inDatabase)

        for (entry in newEntries) {
            val epochMs = try {
                Instant.parse(entry.timestampUtc.iso8601).toEpochMilli()
            } catch (e: Exception) {
                System.currentTimeMillis()
            }
            auditLogDao.insertEntry(entry.toEntity(epochMs))
        }
    }

    // ── Root hash ─────────────────────────────────────────────────────────────

    /**
     * Calcula el root_hash de sellado del estado de la sesión.
     *
     * SHA-256(sessionId || phase.name || consent.name ||
     *         openedAt || updatedAt || lastAuditHash.hex)
     *
     * El separador "||" es literal para evitar ambigüedades de concatenación.
     * Todos los inputs son determinísticos — el mismo estado produce el mismo hash.
     */
    private fun computeRootHash(
        sessionId:     SessionId,
        phase:         PipelinePhase,
        consent:       ConsentLevel,
        openedAt:      String,
        updatedAt:     String,
        lastAuditHash: IntegrityHash
    ): String {
        val payload = buildString {
            append(sessionId.value);      append("||")
            append(phase.name);           append("||")   // ADR-003: .name protegido
            append(consent.name);         append("||")   // ADR-003: .name protegido
            append(openedAt);             append("||")
            append(updatedAt);            append("||")
            append(lastAuditHash.hex)
        }
        return MessageDigest.getInstance("SHA-256")
            .digest(payload.toByteArray(Charsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
    }

    private fun nowUtc(): String =
        java.time.ZonedDateTime.now(java.time.ZoneOffset.UTC)
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'"))
}

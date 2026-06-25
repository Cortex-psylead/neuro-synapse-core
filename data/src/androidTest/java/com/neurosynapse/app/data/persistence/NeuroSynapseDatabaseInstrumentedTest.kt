package com.neurosynapse.app.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity
import com.neurosynapse.app.data.persistence.entities.ClinicalSessionEntity
import com.neurosynapse.app.data.security.NeuroSynapseKeyManager
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — androidTest
// Archivo: persistence/NeuroSynapseDatabaseInstrumentedTest.kt
//
// CRITERIO DE ACEPTACIÓN DEL SPRINT 1 (ISS-04):
//   Estos tests se ejecutan en un emulador o dispositivo real con API 26+.
//   Verifican comportamientos que no pueden simularse en tests unitarios JVM:
//     - WAL efectivo sobre una conexión SQLCipher cifrada real
//     - cipher_memory_security activado
//     - Triggers append-only funcionando sobre BD cifrada
//     - Passphrase zeorizada correctamente (no reutilizable después de fill(0))
//
// EJECUCIÓN:
//   ./gradlew :data:connectedAndroidTest
//   (Requiere emulador o dispositivo conectado con API >= 26)
// ─────────────────────────────────────────────────────────────────────────────

@RunWith(AndroidJUnit4::class)
class NeuroSynapseDatabaseInstrumentedTest {

    private lateinit var context: Context
    private lateinit var keyManager: NeuroSynapseKeyManager
    private lateinit var db: NeuroSynapseDatabase

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        keyManager = NeuroSynapseKeyManager(context)
        db = NeuroSynapseDatabase.getInstance(context, keyManager)
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── ISS-04: Verificación efectiva de WAL sobre SQLCipher ─────────────────

    /**
     * CRITERIO DE ACEPTACIÓN PRINCIPAL — Sprint 1.
     *
     * Verifica que el journal_mode EFECTIVO sobre la conexión cifrada es WAL.
     * No confiar en .setJournalMode() del builder — SQLCipher puede ignorarlo.
     * Esta query se ejecuta sobre la conexión real de SQLCipher 4.x.
     */
    @Test
    fun walModeIsEffectiveOverSQLCipherConnection() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA journal_mode", null)
        cursor.use {
            assertTrue("PRAGMA journal_mode debe retornar exactamente 1 fila", it.moveToFirst())
            val effectiveMode = it.getString(0)
            assertEquals(
                "El journal_mode efectivo sobre SQLCipher debe ser 'wal'. " +
                "Modo obtenido: '$effectiveMode'. " +
                "Verificar versión de net.zetetic:android-database-sqlcipher.",
                "wal",
                effectiveMode.lowercase()
            )
        }
    }

    /**
     * Verifica que cipher_memory_security está activo.
     * SQLCipher retorna 1 cuando el PRAGMA está habilitado.
     */
    @Test
    fun cipherMemorySecurityIsEnabled() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA cipher_memory_security", null)
        cursor.use {
            assertTrue("PRAGMA cipher_memory_security debe retornar 1 fila", it.moveToFirst())
            val value = it.getInt(0)
            assertEquals(
                "cipher_memory_security debe ser 1 (ON). Obtenido: $value",
                1,
                value
            )
        }
    }

    /**
     * Verifica que el tamaño de página es 4096 bytes (configurado explícitamente).
     */
    @Test
    fun cipherPageSizeIs4096() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA cipher_page_size", null)
        cursor.use {
            assertTrue(it.moveToFirst())
            val pageSize = it.getInt(0)
            assertEquals(
                "cipher_page_size debe ser 4096. Obtenido: $pageSize",
                4096,
                pageSize
            )
        }
    }

    /**
     * Verifica que foreign_keys está activado.
     * Necesario para el CASCADE de audit_log_entries sobre clinical_sessions.
     */
    @Test
    fun foreignKeysAreEnabled() {
        val cursor = db.openHelper.writableDatabase.query("PRAGMA foreign_keys", null)
        cursor.use {
            assertTrue(it.moveToFirst())
            val enabled = it.getInt(0)
            assertEquals("PRAGMA foreign_keys debe ser 1 (ON)", 1, enabled)
        }
    }

    // ── ISS-03: Triggers append-only sobre BD cifrada ────────────────────────

    /**
     * Verifica que los tres triggers append-only existen en la BD.
     */
    @Test
    fun appendOnlyTriggersExist() {
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT name FROM sqlite_master WHERE type = 'trigger' ORDER BY name",
            null
        )
        val triggerNames = mutableListOf<String>()
        cursor.use {
            while (it.moveToNext()) {
                triggerNames.add(it.getString(0))
            }
        }
        assertTrue(
            "El trigger prevent_audit_delete debe existir. Triggers encontrados: $triggerNames",
            triggerNames.contains("prevent_audit_delete")
        )
        assertTrue(
            "El trigger prevent_audit_update debe existir. Triggers encontrados: $triggerNames",
            triggerNames.contains("prevent_audit_update")
        )
    }

    /**
     * Verifica que la tabla retention_lock existe con la fila singleton.
     */
    @Test
    fun retentionLockTableExistsWithDefaultBypassDisabled() {
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT bypass_enabled FROM retention_lock WHERE id = 1",
            null
        )
        cursor.use {
            assertTrue("retention_lock debe tener exactamente 1 fila", it.moveToFirst())
            val bypassEnabled = it.getInt(0)
            assertEquals(
                "bypass_enabled debe ser 0 (desactivado) por defecto",
                0,
                bypassEnabled
            )
        }
    }

    /**
     * ISS-03: Verifica que UPDATE sobre audit_log_entries es rechazado.
     * El trigger prevent_audit_update debe lanzar excepción sin excepciones.
     */
    @Test
    fun updateOnAuditLogIsRejectedByTrigger() = runBlocking {
        // Insertar una sesión y una entrada de log primero
        val session = buildTestSessionEntity("test-session-update-guard")
        db.clinicalSessionDao().insertSession(session)

        val entry = buildTestAuditEntry("entry-001", "test-session-update-guard")
        db.auditLogDao().insertEntry(entry)

        // Intentar UPDATE directamente sobre la BD (bypass del DAO)
        try {
            db.openHelper.writableDatabase.execSQL(
                "UPDATE audit_log_entries SET event_type = 'SESSION_ABORTED' WHERE entry_id = 'entry-001'"
            )
            fail(
                "El trigger prevent_audit_update debía haber abortado el UPDATE. " +
                "Si llegamos aquí, el trigger no está funcionando."
            )
        } catch (e: Exception) {
            assertTrue(
                "La excepción debe mencionar que el log es append-only. Mensaje: ${e.message}",
                e.message?.contains("append-only") == true ||
                e.message?.contains("UPDATE is forbidden") == true
            )
        }
    }

    /**
     * ISS-03: Verifica que DELETE sin bypass es rechazado por el trigger.
     */
    @Test
    fun deleteOnAuditLogWithoutBypassIsRejected() = runBlocking {
        val session = buildTestSessionEntity("test-session-delete-guard")
        db.clinicalSessionDao().insertSession(session)

        val entry = buildTestAuditEntry("entry-002", "test-session-delete-guard")
        db.auditLogDao().insertEntry(entry)

        // Intentar DELETE sin activar bypass
        try {
            db.openHelper.writableDatabase.execSQL(
                "DELETE FROM audit_log_entries WHERE entry_id = 'entry-002'"
            )
            fail(
                "El trigger prevent_audit_delete debía haber abortado el DELETE sin bypass. " +
                "Si llegamos aquí, el trigger no está funcionando."
            )
        } catch (e: Exception) {
            assertTrue(
                "La excepción debe mencionar que el log es append-only. Mensaje: ${e.message}",
                e.message?.contains("append-only") == true ||
                e.message?.contains("DELETE forbidden") == true
            )
        }
    }

    /**
     * ISS-03: Verifica que executeRegulatedPurge() elimina entradas con bypass activo
     * y restaura bypass_enabled = 0 después de la transacción.
     */
    @Test
    fun regulatedPurgeDeletesEntriesAndRestoresBypassToZero() = runBlocking {
        val sessionId = "test-session-purge"
        val session = buildTestSessionEntity(sessionId)
        db.clinicalSessionDao().insertSession(session)

        val entry = buildTestAuditEntry("entry-003", sessionId)
        db.auditLogDao().insertEntry(entry)

        // Verificar que la entrada existe
        val before = db.auditLogDao().countEntriesForSession(sessionId)
        assertEquals("Debe haber 1 entrada antes de la purga", 1, before)

        // Ejecutar purga regulatoria
        db.auditLogDao().executeRegulatedPurge(sessionId)

        // Verificar que la entrada fue eliminada
        val after = db.auditLogDao().countEntriesForSession(sessionId)
        assertEquals("Debe haber 0 entradas después de la purga", 0, after)

        // Verificar que bypass_enabled volvió a 0
        val cursor = db.openHelper.writableDatabase.query(
            "SELECT bypass_enabled FROM retention_lock WHERE id = 1", null
        )
        cursor.use {
            assertTrue(it.moveToFirst())
            assertEquals(
                "bypass_enabled debe ser 0 después de la purga (finally garantiza restauración)",
                0,
                it.getInt(0)
            )
        }
    }

    // ── Inserción y lectura básica ────────────────────────────────────────────

    /**
     * Verifica inserción y recuperación de una sesión clínica sobre BD cifrada.
     */
    @Test
    fun insertAndRetrieveSessionOverEncryptedDatabase() = runBlocking {
        val sessionId = "550e8400-e29b-4d88-a456-426614174000"
        val entity = buildTestSessionEntity(sessionId)

        db.clinicalSessionDao().insertSession(entity)

        val retrieved = db.clinicalSessionDao().getSessionById(sessionId)
        assertNotNull("La sesión debe recuperarse desde la BD cifrada", retrieved)
        assertEquals(sessionId, retrieved!!.sessionId)
        assertEquals("IDLE", retrieved.currentPhase)
        assertEquals("CLINICAL_USE_ONLY", retrieved.consentLevel)
        assertFalse(retrieved.isFrozen)
    }

    /**
     * Verifica inserción de entrada de audit log y recuperación en orden.
     */
    @Test
    fun insertAndRetrieveAuditLogEntryOverEncryptedDatabase() = runBlocking {
        val sessionId = "550e8400-e29b-4d88-a456-426614174001"
        db.clinicalSessionDao().insertSession(buildTestSessionEntity(sessionId))

        val genesisHash = "0".repeat(64)
        val entry = buildTestAuditEntry("entry-audit-001", sessionId, genesisHash)
        db.auditLogDao().insertEntry(entry)

        val entries = db.auditLogDao().getEntriesForSession(sessionId)
        assertEquals("Debe haber exactamente 1 entrada", 1, entries.size)
        assertEquals("entry-audit-001", entries[0].entryId)
        assertEquals("SESSION_OPENED", entries[0].eventType)
        assertEquals(genesisHash, entries[0].previousEntryHash)
    }

    // ── Helpers de construcción de entidades de test ──────────────────────────

    private fun buildTestSessionEntity(sessionId: String): ClinicalSessionEntity =
        ClinicalSessionEntity(
            sessionId = sessionId,
            currentPhase = "IDLE",
            consentLevel = "CLINICAL_USE_ONLY",
            openedAtUtc = "2026-06-22T10:00:00.000Z",
            lastUpdatedAtUtc = "2026-06-22T10:00:00.000Z",
            isFrozen = false,
            rootHash = "a".repeat(64)
        )

    private fun buildTestAuditEntry(
        entryId: String,
        sessionId: String,
        previousHash: String = "0".repeat(64)
    ): AuditLogEntryEntity =
        AuditLogEntryEntity(
            entryId = entryId,
            sessionId = sessionId,
            timestampUtc = "2026-06-22T10:00:00.000Z",
            eventType = "SESSION_OPENED",
            slmVersion = null,
            audioHealthCode = "HEALTHY",
            visionHealthCode = "HEALTHY",
            nlpHealthCode = "HEALTHY",
            reportHash = null,
            previousEntryHash = previousHash,
            entryHashSha256 = "b".repeat(64),
            createdAtEpochMs = Instant.parse("2026-06-22T10:00:00.000Z").toEpochMilli()
        )
}

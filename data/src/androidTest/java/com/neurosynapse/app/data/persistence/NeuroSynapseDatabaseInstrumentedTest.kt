package com.neurosynapse.app.data.persistence

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity
import com.neurosynapse.app.data.persistence.entities.ClinicalSessionEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import javax.crypto.KeyGenerator
import javax.crypto.Mac

/**
 * ─────────────────────────────────────────────────────────────────────────────
 * NEURO-SYNAPSE :data — androidTest
 * Archivo: persistence/NeuroSynapseDatabaseInstrumentedTest.kt
 *
 * SPRINT 2 ADAPTATION:
 * Valida el comportamiento seguro de Room + SQLCipher inyectando un motor MAC
 * de prueba simétrico para emular el desbloqueo del hardware criptográfico.
 * ─────────────────────────────────────────────────────────────────────────────
 */
@RunWith(AndroidJUnit4::class)
class NeuroSynapseDatabaseInstrumentedTest {

    private lateinit var db: NeuroSynapseDatabase
    private lateinit var context: Context

    @Before
    fun createDb() {
        context = ApplicationProvider.getApplicationContext()

        // Generar una clave efímera en memoria exclusivamente para el pipeline de pruebas
        val keyGen = KeyGenerator.getInstance("HmacSHA256")
        keyGen.init(256)
        val testSecretKey = keyGen.generateKey()

        // Inicializar el MAC tal como lo haría el BiometricPrompt de producción
        val testMac = Mac.getInstance("HmacSHA256").apply {
            init(testSecretKey)
        }

        // Forzar limpieza de instancias previas para asegurar aislamiento
        val dbClass = NeuroSynapseDatabase::class.java
        val instanceField = dbClass.getDeclaredField("INSTANCE").apply {
            isAccessible = true
        }
        instanceField.set(null, null)

        // Inicializar la base de datos inyectando el objeto criptográfico preparado
        db = NeuroSynapseDatabase.getInstance(context, testMac)
    }

    @After
    fun closeDb() {
        if (::db.isInitialized) {
            db.close()
        }
    }

    @Test
    fun verifyCryptedDatabaseFlowAndAppendOnlyTriggers() = runBlocking {
        val sessionDao = db.clinicalSessionDao()
        val auditDao = db.auditLogDao()

        val sessionId = "session-test-2026"
        val session = ClinicalSessionEntity(
            sessionId = sessionId,
            currentPhase = "ANALYSIS",
            consentLevel = "EXPLICIT_AGREEMENT",
            openedAtUtc = "2026-06-25T00:00:00Z",
            lastUpdatedAtUtc = "2026-06-25T00:00:00Z",
            isFrozen = false,
            rootHash = "b".repeat(64)
        )

        // 1. Validar inserción base
        sessionDao.upsertSession(session)
        
        val auditEntry = AuditLogEntryEntity(
            entryId = "entry-test-001",
            sessionId = sessionId,
            timestampUtc = "2026-06-25T00:01:00Z",
            eventType = "PHASE_TRANSITION",
            slmVersion = "v1.0-test",
            audioHealthCode = "HEALTHY",
            visionHealthCode = "HEALTHY",
            nlpHealthCode = "HEALTHY",
            reportHash = null,
            previousEntryHash = "0".repeat(64),
            entryHashSha256 = "c".repeat(64)
        )
        auditDao.insertEntry(auditEntry)

        // 2. Verificar que el trigger Append-Only bloquea borrados accidentales sin bypass
        try {
            auditDao.deleteEntriesForSession(sessionId)
            fail("El trigger de seguridad debió abortar el DELETE directo sin el bypass regulatorio")
        } catch (e: Exception) {
            // Éxito: el trigger detuvo la alteración del historial clínico
        }

        // 3. Verificar que la purga legal controlada (Transaction Bypass) funciona correctamente
        auditDao.executeRegulatedPurge(sessionId)
        val entries = auditDao.getEntriesForSessionDirect(sessionId)
        assertTrue("La purga regulatoria debió vaciar los registros bajo el amparo legal", entries.isEmpty())
    }
}

package com.neurosynapse.app.data.persistence

import android.content.Context
import android.util.Log
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.neurosynapse.app.data.persistence.dao.AuditLogDao
import com.neurosynapse.app.data.persistence.dao.ClinicalSessionDao
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity
import com.neurosynapse.app.data.persistence.entities.ClinicalSessionEntity
import com.neurosynapse.app.data.security.NeuroSynapseKeyManager
import net.sqlcipher.database.SupportFactory
import javax.crypto.Mac

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/NeuroSynapseDatabase.kt
//
// Base de datos principal del proyecto. Room + SQLCipher 4.x.
//
// EVOLUCIÓN SPRINT 2: Integración con el ciclo de vida del Hardware Biométrico.
//   La base de datos ya no se abre linealmente "en frío". Requiere la inyección
//   de un objeto criptográfico Mac validado previamente por el hardware criptográfico
//   (TEE/StrongBox) tras el escaneo exitoso del terapeuta.
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "NeuroSynapseDB"
private const val DATABASE_NAME = "neuro_synapse_clinical.db"

@Database(
    entities = [
        ClinicalSessionEntity::class,
        AuditLogEntryEntity::class
    ],
    version = 1,
    exportSchema = true
)
abstract class NeuroSynapseDatabase : RoomDatabase() {

    abstract fun clinicalSessionDao(): ClinicalSessionDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {

        @Volatile
        private var INSTANCE: NeuroSynapseDatabase? = null

        /**
         * Obtiene la instancia singleton de la base de datos inyectando el motor criptográfico activo.
         */
        fun getInstance(
            context: Context,
            unlockedMac: Mac
        ): NeuroSynapseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, unlockedMac).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(
            context: Context,
            unlockedMac: Mac
        ): NeuroSynapseDatabase {
            
            // Instanciar un KeyManager local temporal para extraer de forma segura los 32 bytes
            val keyManager = NeuroSynapseKeyManager(context)
            val passphrase: ByteArray = keyManager.derivePassphraseWithUnlockedMac(unlockedMac)

            return try {
                val factory = SupportFactory(passphrase)

                Room.databaseBuilder(
                    context.applicationContext,
                    NeuroSynapseDatabase::class.java,
                    DATABASE_NAME
                )
                    .openHelperFactory(factory)
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)
                    .addMigrations(MIGRATION_1_2)
                    .addCallback(DatabaseCallback())
                    .build()

            } finally {
                // ZEROIZACIÓN INMEDIATA — Cumplimiento estricto de mitigación de memory dumps
                passphrase.fill(0)
            }
        }

        // ── Callback de apertura de BD ────────────────────────────────────────

        private class DatabaseCallback : RoomDatabase.Callback() {

            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                createRetentionLockTable(db)
                createAppendOnlyTriggers(db)
                configurePragmas(db)
                Log.i(TAG, "Base de datos creada con cifrado SQLCipher y triggers append-only.")
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                configurePragmas(db)
                verifyWalMode(db)
                Log.i(TAG, "Base de datos abierta. WAL verificado. cipher_memory_security ON.")
            }

            private fun configurePragmas(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA journal_mode = WAL")
                db.execSQL("PRAGMA cipher_memory_security = ON")
                db.execSQL("PRAGMA cipher_page_size = 4096")
                db.execSQL("PRAGMA foreign_keys = ON")
            }

            private fun verifyWalMode(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA journal_mode", null)
                cursor.use {
                    if (it.moveToFirst()) {
                        val effectiveMode = it.getString(0)
                        check(effectiveMode.equals("wal", ignoreCase = true)) {
                            "FALLO CRÍTICO DE CONFIGURACIÓN: SQLCipher no activó WAL. Modo efectivo: '$effectiveMode'."
                        }
                    } else {
                        throw IllegalStateException("PRAGMA journal_mode no retornó ningún resultado.")
                    }
                }
            }

            private fun createRetentionLockTable(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS retention_lock (
                        id      INTEGER PRIMARY KEY CHECK (id = 1),
                        bypass_enabled INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                db.execSQL("""
                    INSERT OR IGNORE INTO retention_lock (id, bypass_enabled) VALUES (1, 0)
                """.trimIndent())
            }

            private fun createAppendOnlyTriggers(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_audit_update
                    BEFORE UPDATE ON audit_log_entries
                    BEGIN
                        SELECT RAISE(ABORT, 'audit_log_entries is append-only: UPDATE is forbidden');
                    END
                """.trimIndent())

                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
                    BEFORE DELETE ON audit_log_entries
                    BEGIN
                        SELECT CASE
                            WHEN (SELECT bypass_enabled FROM retention_lock WHERE id = 1) = 0
                            THEN RAISE(ABORT, 'audit_log_entries is append-only: DELETE forbidden without retention bypass')
                        END;
                    END
                """.trimIndent())
            }
        }

        // ── Migrations ────────────────────────────────────────────────────────

        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 2: Estructura base lista para evolución de esquemas clínicos
            }
        }
    }
}

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

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/NeuroSynapseDatabase.kt
//
// Base de datos principal del proyecto. Room + SQLCipher 4.x.
//
// RESOLUCIÓN ISS-04: WAL y cipher_memory_security configurados explícitamente
// en el callback onCreate/onOpen — no se asume que .setJournalMode() sea
// suficiente sobre una conexión cifrada con SQLCipher.
//
// El callback onOpen verifica el journal_mode efectivo y lanza excepción
// si SQLCipher no activó WAL. Esto convierte el criterio de aceptación
// del Sprint 1 en una verificación en tiempo de ejecución, no sólo en tests.
//
// RESOLUCIÓN ISS-03: Los triggers append-only y la tabla retention_lock
// se crean en onCreate. Los triggers verifican bypass_enabled antes de
// permitir DELETEs sobre audit_log_entries.
//
// CIFRADO:
//   SupportFactory de SQLCipher recibe la passphrase como ByteArray.
//   El ByteArray se zeoriza inmediatamente después de pasarlo al factory
//   para minimizar el tiempo que la passphrase existe en memoria.
//   La passphrase nunca se almacena como String (los Strings son inmutables
//   en JVM y pueden quedar en el String Pool indefinidamente).
// ─────────────────────────────────────────────────────────────────────────────

private const val TAG = "NeuroSynapseDB"
private const val DATABASE_NAME = "neuro_synapse_clinical.db"

@Database(
    entities = [
        ClinicalSessionEntity::class,
        AuditLogEntryEntity::class
    ],
    version = 1,
    exportSchema = true   // Exporta schema JSON para documentación de migrations
)
abstract class NeuroSynapseDatabase : RoomDatabase() {

    abstract fun clinicalSessionDao(): ClinicalSessionDao
    abstract fun auditLogDao(): AuditLogDao

    companion object {

        @Volatile
        private var INSTANCE: NeuroSynapseDatabase? = null

        /**
         * Obtiene la instancia singleton de la base de datos.
         *
         * Patrón Double-Checked Locking con @Volatile.
         * Nota arquitectónica (revisión ADR-002):
         *   synchronized(this) es correcto aquí porque es bootstrap de
         *   infraestructura (una sola inicialización), no lógica de dominio.
         *   No se usa Mutex de coroutines porque Room.databaseBuilder es
         *   una operación síncrona de configuración, no una suspending function.
         *
         * @param context ApplicationContext — nunca Activity/Fragment context.
         * @param keyManager Para derivar la passphrase SQLCipher.
         */
        fun getInstance(
            context: Context,
            keyManager: NeuroSynapseKeyManager
        ): NeuroSynapseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, keyManager).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(
            context: Context,
            keyManager: NeuroSynapseKeyManager
        ): NeuroSynapseDatabase {

            // ISS-04: La passphrase se deriva aquí, se usa inmediatamente,
            // y se zeoriza antes de que buildDatabase() retorne.
            val passphrase: ByteArray = keyManager.deriveSQLCipherPassphrase()

            return try {
                val factory = SupportFactory(passphrase)

                Room.databaseBuilder(
                    context.applicationContext,
                    NeuroSynapseDatabase::class.java,
                    DATABASE_NAME
                )
                    // Aplicar cifrado SQLCipher AES-256 con la passphrase derivada
                    .openHelperFactory(factory)

                    // ISS-04: setJournalMode() es insuficiente sobre SQLCipher.
                    // WAL se fuerza explícitamente en addCallback (ver DatabaseCallback).
                    // Declaramos aquí la intención; el callback la hace efectiva y verificable.
                    .setJournalMode(JournalMode.WRITE_AHEAD_LOGGING)

                    // Migrations — NUNCA fallbackToDestructiveMigration() en producción
                    .addMigrations(MIGRATION_1_2)

                    // Callback: configura PRAGMAs de seguridad y crea triggers append-only
                    .addCallback(DatabaseCallback())

                    .build()

            } finally {
                // ZEROIZACIÓN INMEDIATA — la passphrase no debe sobrevivir
                // más allá de la inicialización de SupportFactory.
                // SupportFactory hace su copia interna — este array ya no es necesario.
                passphrase.fill(0)
            }
        }

        // ── Callback de apertura de BD ────────────────────────────────────────

        private class DatabaseCallback : RoomDatabase.Callback() {

            /**
             * onCreate: se ejecuta la primera vez que se crea la BD.
             * Aquí se crean la tabla retention_lock y los triggers append-only.
             */
            override fun onCreate(db: SupportSQLiteDatabase) {
                super.onCreate(db)
                createRetentionLockTable(db)
                createAppendOnlyTriggers(db)
                configurePragmas(db)
                Log.i(TAG, "Base de datos creada con cifrado SQLCipher y triggers append-only.")
            }

            /**
             * onOpen: se ejecuta en CADA apertura de la BD (arranque de la app).
             *
             * ISS-04 RESUELTO: configurePragmas() fuerza WAL y cipher_memory_security,
             * luego verifyWalMode() verifica que SQLCipher aplicó WAL efectivamente.
             * Si WAL no está activo, lanza IllegalStateException — la app no puede
             * abrir la BD en un estado de seguridad comprometido.
             */
            override fun onOpen(db: SupportSQLiteDatabase) {
                super.onOpen(db)
                configurePragmas(db)
                verifyWalMode(db)
                Log.i(TAG, "Base de datos abierta. WAL verificado. cipher_memory_security ON.")
            }

            /**
             * Configura los PRAGMAs de seguridad y rendimiento de SQLCipher.
             *
             * PRAGMA journal_mode = WAL:
             *   Write-Ahead Logging — lecturas y escrituras no se bloquean entre sí.
             *   Mejora el rendimiento en el pipeline clínico donde el orquestador
             *   escribe el audit log mientras la UI lee el estado de la sesión.
             *   SQLCipher 4.x soporta WAL sobre páginas cifradas.
             *
             * PRAGMA cipher_memory_security = ON:
             *   SQLCipher zeoriza las páginas de la BD en memoria cuando las libera.
             *   Mitiga ataques de volcado de memoria (memory dump) que podrían
             *   exponer datos clínicos o la passphrase en RAM.
             *   Alineado con el protocolo de RAM Zeroing del Master Prompt.
             *
             * PRAGMA cipher_page_size = 4096:
             *   Tamaño de página cifrada. 4096 bytes es el default de SQLCipher 4.x
             *   y el óptimo para la mayoría de dispositivos ARM64.
             *   Declarado explícitamente para que el schema sea reproducible
             *   independientemente de futuras versiones de SQLCipher.
             *
             * PRAGMA foreign_keys = ON:
             *   SQLite desactiva las foreign keys por defecto.
             *   Requerido para que el CASCADE de audit_log_entries funcione.
             */
            private fun configurePragmas(db: SupportSQLiteDatabase) {
                // WAL: debe ejecutarse antes de cualquier otra operación de escritura
                db.execSQL("PRAGMA journal_mode = WAL")

                // Seguridad de memoria: zeoriza páginas al liberarlas
                db.execSQL("PRAGMA cipher_memory_security = ON")

                // Tamaño de página explícito (sólo efectivo en onCreate)
                db.execSQL("PRAGMA cipher_page_size = 4096")

                // Foreign keys para CASCADE de audit_log_entries
                db.execSQL("PRAGMA foreign_keys = ON")
            }

            /**
             * ISS-04: Verificación efectiva del modo WAL sobre SQLCipher.
             *
             * No asumimos que PRAGMA journal_mode = WAL fue aceptado.
             * SQLCipher tiene historial de silenciar el cambio de modo en ciertas
             * condiciones (BD ya abierta por otro proceso, versión con bug, etc.).
             *
             * Esta verificación convierte el criterio de aceptación del Sprint 1
             * en una guardia en tiempo de ejecución: si WAL no está activo,
             * la app lanza IllegalStateException con mensaje diagnóstico claro.
             *
             * @throws IllegalStateException si SQLCipher no activó WAL.
             */
            private fun verifyWalMode(db: SupportSQLiteDatabase) {
                val cursor = db.query("PRAGMA journal_mode", null)
                cursor.use {
                    if (it.moveToFirst()) {
                        val effectiveMode = it.getString(0)
                        check(effectiveMode.equals("wal", ignoreCase = true)) {
                            "FALLO CRÍTICO DE CONFIGURACIÓN: SQLCipher no activó WAL. " +
                            "Modo efectivo: '$effectiveMode'. " +
                            "Esto puede indicar una versión incompatible de SQLCipher " +
                            "o una BD existente con journal_mode diferente. " +
                            "Acción requerida: eliminar la BD y reinstalar."
                        }
                    } else {
                        throw IllegalStateException(
                            "PRAGMA journal_mode no retornó ningún resultado. " +
                            "La conexión SQLCipher puede estar en estado inválido."
                        )
                    }
                }
            }

            /**
             * ISS-03: Crea la tabla retention_lock para el mecanismo bypass.
             *
             * La constraint CHECK (id = 1) garantiza que esta tabla tenga
             * exactamente una fila — la bandera de bypass es un singleton.
             */
            private fun createRetentionLockTable(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS retention_lock (
                        id      INTEGER PRIMARY KEY CHECK (id = 1),
                        bypass_enabled INTEGER NOT NULL DEFAULT 0
                    )
                """.trimIndent())

                // Insertar la fila singleton si no existe
                db.execSQL("""
                    INSERT OR IGNORE INTO retention_lock (id, bypass_enabled) VALUES (1, 0)
                """.trimIndent())
            }

            /**
             * ISS-03: Crea los triggers que hacen audit_log_entries append-only.
             *
             * prevent_audit_update:
             *   Bloquea CUALQUIER UPDATE sobre el log — sin excepciones.
             *   Ninguna entrada del log puede modificarse nunca.
             *
             * prevent_audit_delete:
             *   Bloquea DELETE a menos que bypass_enabled = 1 en retention_lock.
             *   La única forma de activar bypass_enabled = 1 es a través de
             *   AuditLogDao.enableRetentionBypass() llamado por el gateway
             *   dentro de una transacción atómica (executeRegulatedPurge).
             */
            private fun createAppendOnlyTriggers(db: SupportSQLiteDatabase) {
                // Trigger UPDATE — sin bypass posible
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_audit_update
                    BEFORE UPDATE ON audit_log_entries
                    BEGIN
                        SELECT RAISE(
                            ABORT,
                            'audit_log_entries is append-only: UPDATE is forbidden'
                        );
                    END
                """.trimIndent())

                // Trigger DELETE — con bypass regulatorio condicional
                db.execSQL("""
                    CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
                    BEFORE DELETE ON audit_log_entries
                    BEGIN
                        SELECT CASE
                            WHEN (
                                SELECT bypass_enabled
                                FROM retention_lock
                                WHERE id = 1
                            ) = 0
                            THEN RAISE(
                                ABORT,
                                'audit_log_entries is append-only: DELETE forbidden without retention bypass'
                            )
                        END;
                    END
                """.trimIndent())
            }
        }

        // ── Migrations ────────────────────────────────────────────────────────

        /**
         * Migration placeholder para schema version 1 → 2.
         * En el MVP (version = 1) esta migration no se ejecutará.
         * Se define ahora para establecer el patrón de migrations futuras
         * y evitar el anti-patrón fallbackToDestructiveMigration().
         *
         * Cuando se añadan columnas o tablas nuevas en version = 2,
         * esta migration se implementará con los ALTER TABLE necesarios.
         */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // Version 2: pendiente de definición en Sprint 2.
                // Los triggers y retention_lock ya se crean en onCreate,
                // no en migrations, porque pertenecen a la version 1.
            }
        }
    }
}

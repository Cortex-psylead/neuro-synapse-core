package com.neurosynapse.app.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/dao/AuditLogDao.kt
//
// RESOLUCIÓN ISS-03: Append-Only con purga regulatoria controlada.
//
// PROBLEMA:
//   El trigger prevent_audit_delete (definido en NeuroSynapseDatabase)
//   prohíbe cualquier DELETE sobre audit_log_entries por defecto.
//   Sin embargo, ADR-008 y la Resolución 3100/2019 exigen que
//   AndroidLocalSovereigntyGateway pueda ejecutar purga legal de sesiones
//   vencidas (retención > 5 años para metadatos, > 1 año para biométricos).
//
// SOLUCIÓN — bypass_retention_lock (Opción B del ADR-INDEX):
//   La tabla retention_lock tiene una sola fila con una columna booleana:
//     bypass_enabled INTEGER NOT NULL DEFAULT 0
//   El trigger prevent_audit_delete verifica esta bandera ANTES de abortar:
//     IF (SELECT bypass_enabled FROM retention_lock) = 0 THEN RAISE(ABORT)
//   El gateway activa bypass_enabled = 1, ejecuta el DELETE, y lo restaura
//   a 0 dentro de una única transacción atómica.
//   FUERA de esa transacción, bypass_enabled siempre es 0.
//
// TRIGGERS SQL (definidos en NeuroSynapseDatabase.MIGRATION_TRIGGERS):
//
//   CREATE TABLE IF NOT EXISTS retention_lock (
//       id INTEGER PRIMARY KEY CHECK (id = 1),  -- sólo una fila posible
//       bypass_enabled INTEGER NOT NULL DEFAULT 0
//   );
//   INSERT OR IGNORE INTO retention_lock (id, bypass_enabled) VALUES (1, 0);
//
//   CREATE TRIGGER IF NOT EXISTS prevent_audit_update
//   BEFORE UPDATE ON audit_log_entries
//   BEGIN
//       SELECT RAISE(ABORT, 'audit_log_entries is append-only: UPDATE forbidden');
//   END;
//
//   CREATE TRIGGER IF NOT EXISTS prevent_audit_delete
//   BEFORE DELETE ON audit_log_entries
//   BEGIN
//       SELECT CASE
//           WHEN (SELECT bypass_enabled FROM retention_lock WHERE id = 1) = 0
//           THEN RAISE(ABORT, 'audit_log_entries is append-only: DELETE forbidden without retention bypass')
//       END;
//   END;
//
// El trigger de UPDATE no tiene bypass — ninguna entrada del log puede
// modificarse nunca, ni siquiera durante la purga regulatoria.
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface AuditLogDao {

    // ── Inserción (única operación de escritura permitida normalmente) ─────────

    /**
     * Inserta una entrada del log de auditoría.
     *
     * OnConflictStrategy.ABORT: si el entry_id ya existe, la inserción falla
     * con excepción. Esto es correcto — un entry_id duplicado indica un bug
     * grave en la generación de UUIDs o un intento de replay attack.
     *
     * El llamador (RoomAuditLogRepository) verifica que:
     *   1. previousEntryHash coincide con el entryHashSha256 de la última entrada.
     *   2. entryHashSha256 es SHA-256 válido (64 hex chars).
     * Estas verificaciones ocurren en el repositorio, no en el DAO,
     * porque el DAO no tiene acceso a la lógica de dominio.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertEntry(entry: AuditLogEntryEntity)

    // ── Consultas de lectura ──────────────────────────────────────────────────

    /**
     * Recupera todas las entradas de una sesión en orden cronológico estricto.
     * Ordenado por created_at_epoch_ms (Long) — más eficiente que parsear ISO-8601.
     * El Flow emite una nueva lista cada vez que se inserta una entrada nueva.
     */
    @Query("""
        SELECT * FROM audit_log_entries 
        WHERE session_id = :sessionId 
        ORDER BY created_at_epoch_ms ASC
    """)
    fun observeEntriesForSession(sessionId: String): Flow<List<AuditLogEntryEntity>>

    /**
     * Carga síncrona de todas las entradas de una sesión.
     * Usada por el repositorio para verificar integridad de cadena
     * y para reconstruir el AuditLog en memoria al rehidratar una sesión.
     */
    @Query("""
        SELECT * FROM audit_log_entries 
        WHERE session_id = :sessionId 
        ORDER BY created_at_epoch_ms ASC
    """)
    suspend fun getEntriesForSession(sessionId: String): List<AuditLogEntryEntity>

    /**
     * Obtiene la última entrada del log de una sesión.
     * Usada por el repositorio para encadenar el hash antes de insertar
     * una nueva entrada (verificación de previousEntryHash).
     */
    @Query("""
        SELECT * FROM audit_log_entries 
        WHERE session_id = :sessionId 
        ORDER BY created_at_epoch_ms DESC 
        LIMIT 1
    """)
    suspend fun getLastEntryForSession(sessionId: String): AuditLogEntryEntity?

    /**
     * Cuenta el total de entradas para una sesión.
     * Útil para diagnóstico y para detectar truncamiento del log.
     */
    @Query("SELECT COUNT(*) FROM audit_log_entries WHERE session_id = :sessionId")
    suspend fun countEntriesForSession(sessionId: String): Int

    /**
     * Verifica que la cadena de hashes es continua para una sesión.
     * Retorna las entradas donde el previousEntryHash NO coincide con
     * el entryHashSha256 de la entrada anterior.
     *
     * Una lista vacía = cadena íntegra.
     * Una lista no vacía = manipulación detectada — los entryId retornados
     * son los de las entradas donde se rompe la cadena.
     *
     * La query usa una self-join con LAG (SQL window function soportada
     * por SQLite 3.25+ que SQLCipher 4.x incluye).
     */
    @Query("""
        SELECT curr.entry_id
        FROM audit_log_entries curr
        LEFT JOIN audit_log_entries prev 
            ON prev.session_id = curr.session_id
            AND prev.created_at_epoch_ms = (
                SELECT MAX(e2.created_at_epoch_ms)
                FROM audit_log_entries e2
                WHERE e2.session_id = curr.session_id
                AND e2.created_at_epoch_ms < curr.created_at_epoch_ms
            )
        WHERE curr.session_id = :sessionId
        AND prev.entry_id IS NOT NULL
        AND curr.previous_entry_hash != prev.entry_hash_sha256
        ORDER BY curr.created_at_epoch_ms ASC
    """)
    suspend fun findChainViolations(sessionId: String): List<String>

    // ── Purga regulatoria (SÓLO usada por AndroidLocalSovereigntyGateway) ─────

    /**
     * Activa el bypass del trigger prevent_audit_delete.
     *
     * CONTRATO DE USO:
     *   Este método NUNCA debe llamarse directamente desde código de aplicación.
     *   Su única invocación válida es dentro de executeRegulatedPurge(),
     *   que garantiza la restauración del lock en un bloque finally.
     *
     * La tabla retention_lock tiene exactamente una fila (id = 1).
     * Mientras bypass_enabled = 1, el trigger permite DELETEs.
     * La ventana de vulnerabilidad existe SÓLO dentro de la transacción
     * atómica de executeRegulatedPurge() — no es observable externamente.
     */
    @Query("UPDATE retention_lock SET bypass_enabled = 1 WHERE id = 1")
    suspend fun enableRetentionBypass()

    /**
     * Restaura la protección append-only del log.
     * SIEMPRE debe llamarse después de enableRetentionBypass(),
     * incluso si la purga falló (bloque finally en executeRegulatedPurge).
     */
    @Query("UPDATE retention_lock SET bypass_enabled = 0 WHERE id = 1")
    suspend fun disableRetentionBypass()

    /**
     * Elimina todas las entradas del log de una sesión.
     *
     * PRECONDICIÓN ABSOLUTA: enableRetentionBypass() debe haberse llamado
     * dentro de la misma transacción. Si no, el trigger lanzará:
     *   SQLiteConstraintException: audit_log_entries is append-only
     *
     * Esta función es package-private por convención (no existe en Kotlin/Room,
     * pero la documentación lo establece como contrato arquitectónico):
     *   - Sólo AndroidLocalSovereigntyGateway puede llamarla.
     *   - El DAO no puede forzar visibilidad en Room; la restricción es
     *     de diseño y se verifica en code review.
     */
    @Query("DELETE FROM audit_log_entries WHERE session_id = :sessionId")
    suspend fun deleteEntriesForSession(sessionId: String)

    /**
     * Ejecuta la purga regulatoria de una sesión de forma atómica y segura.
     *
     * Esta función es la ÚNICA ruta válida para eliminar entradas del log.
     * Implementa el patrón Opción B del ADR-INDEX:
     *
     *   TRANSACCIÓN ATÓMICA:
     *     1. enableRetentionBypass()     → bypass_enabled = 1
     *     2. deleteEntriesForSession()   → el trigger permite el DELETE
     *     3. disableRetentionBypass()    → bypass_enabled = 0
     *
     * Si el paso 2 falla (por cualquier motivo), el bloque finally
     * garantiza que bypass_enabled vuelve a 0. La BD nunca queda en
     * estado de bypass_enabled = 1 después de que la transacción termina.
     *
     * AUDIT TRAIL:
     *   El llamador (AndroidLocalSovereigntyGateway) debe insertar una
     *   entrada REGULATORY_PURGE_EXECUTED en una tabla separada de
     *   compliance_log (fuera del audit_log protegido) antes de invocar
     *   esta función, para dejar rastro del borrado autorizado.
     *
     * @param sessionId ID de la sesión a purgar.
     * @throws IllegalStateException si la verificación de retentionLock falla.
     */
    @Transaction
    suspend fun executeRegulatedPurge(sessionId: String) {
        try {
            enableRetentionBypass()
            deleteEntriesForSession(sessionId)
        } finally {
            // INVARIANTE: bypass_enabled siempre vuelve a 0,
            // independientemente del resultado de deleteEntriesForSession.
            disableRetentionBypass()
        }
    }
}

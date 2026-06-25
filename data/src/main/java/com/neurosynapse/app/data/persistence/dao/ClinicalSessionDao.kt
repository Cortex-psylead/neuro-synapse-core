package com.neurosynapse.app.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import com.neurosynapse.app.data.persistence.entities.ClinicalSessionEntity
import kotlinx.coroutines.flow.Flow

// ─────────────────────────────────────────────────────────────────────────────
// NEURO-SYNAPSE :data — persistence/dao/ClinicalSessionDao.kt
//
// DAO para operaciones CRUD sobre clinical_sessions.
//
// A diferencia de AuditLogDao, las sesiones SÍ pueden actualizarse:
//   - Cada transición de fase actualiza current_phase y last_updated_at_utc.
//   - El root_hash se recalcula en cada actualización.
//   - is_frozen cambia a true cuando la sesión llega a COMPLETED o ABORTED.
//
// Lo que NUNCA cambia:
//   - session_id (PrimaryKey)
//   - opened_at_utc (timestamp de creación — inmutable)
//
// Las sesiones se BORRAN únicamente via purga regulatoria (ADR-008),
// que activa el bypass_retention_lock antes de ejecutar el DELETE.
// El ForeignKey CASCADE en audit_log_entries borra el log en cascada.
// ─────────────────────────────────────────────────────────────────────────────

@Dao
interface ClinicalSessionDao {

    // ── Inserción ─────────────────────────────────────────────────────────────

    /**
     * Inserta una nueva sesión clínica.
     *
     * OnConflictStrategy.ABORT: un session_id duplicado es un bug grave.
     * Nunca debe ocurrir si SessionId se genera con UUID v4 aleatorio.
     * La excepción resultante sube hasta el repositorio para ser manejada.
     */
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ClinicalSessionEntity)

    // ── Actualización ─────────────────────────────────────────────────────────

    /**
     * Actualiza el estado de una sesión existente.
     * Room genera el UPDATE WHERE session_id = :session.sessionId.
     * Si la sesión no existe, retorna 0 filas afectadas (no lanza excepción).
     */
    @Update
    suspend fun updateSession(session: ClinicalSessionEntity): Int

    /**
     * Actualización eficiente sólo de los campos que cambian en cada
     * transición de fase — evita leer y reescribir campos estáticos.
     *
     * @param sessionId ID de la sesión a actualizar.
     * @param currentPhase Nombre del nuevo PipelinePhase (ADR-003: .name).
     * @param lastUpdatedAtUtc Timestamp UTC ISO-8601 de la actualización.
     * @param isFrozen true si la nueva fase es COMPLETED o ABORTED.
     * @param rootHash Hash SHA-256 del nuevo estado sellado.
     */
    @Query("""
        UPDATE clinical_sessions 
        SET current_phase = :currentPhase,
            last_updated_at_utc = :lastUpdatedAtUtc,
            is_frozen = :isFrozen,
            root_hash = :rootHash
        WHERE session_id = :sessionId
    """)
    suspend fun updatePhaseAndHash(
        sessionId: String,
        currentPhase: String,
        lastUpdatedAtUtc: String,
        isFrozen: Boolean,
        rootHash: String
    ): Int

    // ── Consultas por ID ──────────────────────────────────────────────────────

    /**
     * Recupera una sesión por su ID. Retorna null si no existe.
     * Usado por el repositorio para rehidratar la sesión en memoria.
     */
    @Query("SELECT * FROM clinical_sessions WHERE session_id = :sessionId")
    suspend fun getSessionById(sessionId: String): ClinicalSessionEntity?

    /**
     * Observa una sesión en tiempo real. Emite cada vez que la fila cambia.
     * El ViewModel puede coleccionar este Flow para reaccionar a cambios de fase.
     */
    @Query("SELECT * FROM clinical_sessions WHERE session_id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<ClinicalSessionEntity?>

    // ── Consultas de listado ──────────────────────────────────────────────────

    /**
     * Lista todos los session_id almacenados.
     * Devuelve sólo IDs — no carga las sesiones completas (eficiencia de RAM).
     */
    @Query("SELECT session_id FROM clinical_sessions ORDER BY opened_at_utc DESC")
    suspend fun getAllSessionIds(): List<String>

    /**
     * Lista sesiones activas (no congeladas).
     * is_frozen = 0 → sesión en curso (IDLE, CAPTURE, ACOUSTIC_ANALYSIS, etc.)
     */
    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE is_frozen = 0 
        ORDER BY opened_at_utc DESC
    """)
    fun observeActiveSessions(): Flow<List<ClinicalSessionEntity>>

    /**
     * Lista sesiones completadas con reporte generado, ordenadas por fecha.
     * is_frozen = 1 AND current_phase = 'COMPLETED'
     */
    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE is_frozen = 1 AND current_phase = 'COMPLETED'
        ORDER BY last_updated_at_utc DESC
    """)
    fun observeCompletedSessions(): Flow<List<ClinicalSessionEntity>>

    /**
     * Lista sesiones abortadas para diagnóstico y posible recuperación manual.
     */
    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE current_phase = 'ABORTED'
        ORDER BY last_updated_at_utc DESC
    """)
    suspend fun getAbortedSessions(): List<ClinicalSessionEntity>

    /**
     * Recupera sesiones abiertas antes de una fecha dada.
     * Usado por RetentionPolicyWorker para identificar sesiones candidatas
     * a purga por cumplimiento de período de retención (ADR-008).
     *
     * @param cutoffUtc Timestamp ISO-8601 del límite de retención.
     *                  Ej: "2021-06-22T00:00:00.000Z" para retención de 5 años.
     */
    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE opened_at_utc < :cutoffUtc
        AND is_frozen = 1
        ORDER BY opened_at_utc ASC
    """)
    suspend fun getSessionsOlderThan(cutoffUtc: String): List<ClinicalSessionEntity>

    // ── Verificación de integridad ────────────────────────────────────────────

    /**
     * Verifica que el root_hash almacenado coincide con el valor esperado.
     * Retorna true si coincide, false si hay discrepancia (posible manipulación).
     *
     * El repositorio recalcula el rootHash desde los datos de dominio y
     * lo compara contra el valor almacenado en BD antes de retornar la sesión.
     */
    @Query("""
        SELECT COUNT(*) FROM clinical_sessions 
        WHERE session_id = :sessionId AND root_hash = :expectedRootHash
    """)
    suspend fun verifyRootHash(sessionId: String, expectedRootHash: String): Int

    // ── Purga regulatoria (sólo AndroidLocalSovereigntyGateway) ─────────────

    /**
     * Elimina una sesión.
     *
     * PRECONDICIÓN: retention_lock.bypass_enabled = 1 debe estar activo.
     * El borrado en cascada de audit_log_entries ocurre automáticamente
     * por el ForeignKey CASCADE definido en AuditLogEntryEntity.
     *
     * Esta función NUNCA debe llamarse directamente desde código de aplicación.
     * La ruta correcta es: AndroidLocalSovereigntyGateway.secureDeleteSession()
     * que activa el bypass, borra la sesión (y en cascada su log), y
     * desactiva el bypass dentro de una única transacción atómica.
     */
    @Query("DELETE FROM clinical_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String)

    /**
     * Upsert atómico: inserta si no existe, actualiza si ya existe.
     * Usado por el repositorio en save() para manejar ambos casos
     * (primera persistencia y actualizaciones posteriores) sin lógica condicional.
     *
     * OnConflictStrategy.REPLACE reemplaza la fila completa en conflicto.
     * Válido aquí porque la sesión como entidad es mutable (a diferencia
     * del audit log que es append-only).
     */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ClinicalSessionEntity)

    // ── Estadísticas (para dashboard del terapeuta) ───────────────────────────

    /**
     * Cuenta el total de sesiones por fase.
     * Usado por el dashboard de actividad del terapeuta en la UI.
     */
    @Query("SELECT current_phase, COUNT(*) as count FROM clinical_sessions GROUP BY current_phase")
    suspend fun countByPhase(): List<PhaseCount>
}

/**
 * Resultado de la query de conteo por fase.
 * No es una entidad de Room — es un projection DTO.
 */
data class PhaseCount(
    val currentPhase: String,
    val count: Int
)

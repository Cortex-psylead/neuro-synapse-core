package com.neurosynapse.app.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import com.neurosynapse.app.data.persistence.entities.ClinicalSessionEntity
import kotlinx.coroutines.flow.Flow

/**
 * DAO para la gestión de sesiones clínicas.
 * Controla el ciclo de vida de la persistencia de la sesión y su integridad.
 */
@Dao
interface ClinicalSessionDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertSession(session: ClinicalSessionEntity): Long

    @Update
    suspend fun updateSession(session: ClinicalSessionEntity): Int

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

    @Query("SELECT * FROM clinical_sessions WHERE session_id = :sessionId")
    suspend fun getSessionById(sessionId: String): ClinicalSessionEntity?

    @Query("SELECT * FROM clinical_sessions WHERE session_id = :sessionId")
    fun observeSessionById(sessionId: String): Flow<ClinicalSessionEntity?>

    @Query("SELECT session_id FROM clinical_sessions ORDER BY opened_at_utc DESC")
    suspend fun getAllSessionIds(): List<String>

    @Query("SELECT * FROM clinical_sessions WHERE is_frozen = 0 ORDER BY opened_at_utc DESC")
    fun observeActiveSessions(): Flow<List<ClinicalSessionEntity>>

    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE is_frozen = 1 AND current_phase = 'COMPLETED'
        ORDER BY last_updated_at_utc DESC
    """)
    fun observeCompletedSessions(): Flow<List<ClinicalSessionEntity>>

    @Query("SELECT * FROM clinical_sessions WHERE current_phase = 'ABORTED' ORDER BY last_updated_at_utc DESC")
    suspend fun getAbortedSessions(): List<ClinicalSessionEntity>

    @Query("""
        SELECT * FROM clinical_sessions 
        WHERE opened_at_utc < :cutoffUtc
        AND is_frozen = 1
        ORDER BY opened_at_utc ASC
    """)
    suspend fun getSessionsOlderThan(cutoffUtc: String): List<ClinicalSessionEntity>

    @Query("""
        SELECT COUNT(*) FROM clinical_sessions 
        WHERE session_id = :sessionId AND root_hash = :expectedRootHash
    """)
    suspend fun verifyRootHash(sessionId: String, expectedRootHash: String): Int

    @Query("DELETE FROM clinical_sessions WHERE session_id = :sessionId")
    suspend fun deleteSession(sessionId: String): Int

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSession(session: ClinicalSessionEntity): Long

    /**
     * Cuenta el total de sesiones por fase.
     * Corregido: SQL Alias 'AS currentPhase' para mapeo directo con el DTO.
     */
    @Query("SELECT current_phase AS currentPhase, COUNT(*) as count FROM clinical_sessions GROUP BY current_phase")
    suspend fun countByPhase(): List<PhaseCount>
}

/**
 * DTO para el conteo por fase.
 */
data class PhaseCount(
    val currentPhase: String,
    val count: Int
)

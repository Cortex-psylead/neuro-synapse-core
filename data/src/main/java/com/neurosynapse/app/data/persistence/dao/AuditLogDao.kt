package com.neurosynapse.app.data.persistence.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import com.neurosynapse.app.data.persistence.entities.AuditLogEntryEntity

@Dao
abstract class AuditLogDao {

    @Insert(onConflict = OnConflictStrategy.ABORT)
    abstract suspend fun insertEntry(entry: AuditLogEntryEntity): Long

    @Query("SELECT * FROM audit_log_entries WHERE session_id = :sessionId ORDER BY timestamp_utc ASC")
    abstract suspend fun getEntriesForSession(sessionId: String): List<AuditLogEntryEntity>

    @Query("SELECT COUNT(*) FROM audit_log_entries WHERE session_id = :sessionId")
    abstract suspend fun countEntriesForSession(sessionId: String): Int

    /**
     * PURGA REGULATORIA (ADR-008):
     * Desbloquea el trigger append-only, borra los logs y vuelve a bloquear.
     * Solo ejecutable dentro de una transacción para garantizar integridad.
     */
    @Transaction
    open suspend fun executeRegulatedPurge(sessionId: String) {
        enableRetentionBypass()
        deleteLogsForSession(sessionId)
        disableRetentionBypass()
    }

    @Query("UPDATE retention_lock SET bypass_enabled = 1 WHERE id = 1")
    abstract suspend fun enableRetentionBypass(): Int

    @Query("UPDATE retention_lock SET bypass_enabled = 0 WHERE id = 1")
    abstract suspend fun disableRetentionBypass(): Int

    @Query("DELETE FROM audit_log_entries WHERE session_id = :sessionId")
    abstract suspend fun deleteLogsForSession(sessionId: String): Int
}

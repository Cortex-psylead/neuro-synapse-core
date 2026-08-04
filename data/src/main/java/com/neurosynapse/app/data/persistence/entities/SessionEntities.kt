package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(tableName = "clinical_sessions")
data class ClinicalSessionEntity(
    @PrimaryKey
    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "current_phase")
    val currentPhase: String,

    @ColumnInfo(name = "consent_level")
    val consentLevel: String,

    @ColumnInfo(name = "opened_at_utc")
    val openedAtUtc: String,

    @ColumnInfo(name = "last_updated_at_utc")
    val lastUpdatedAtUtc: String,

    @ColumnInfo(name = "is_frozen")
    val isFrozen: Boolean,

    @ColumnInfo(name = "root_hash")
    val rootHash: String
)

@Entity(
    tableName = "audit_log_entries",
    foreignKeys = [
        ForeignKey(
            entity = ClinicalSessionEntity::class,
            parentColumns = ["session_id"],
            childColumns = ["session_id"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index(value = ["session_id"])]
)
data class AuditLogEntryEntity(
    @PrimaryKey
    @ColumnInfo(name = "entry_id")
    val entryId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "timestamp_utc")
    val timestampUtc: String,

    @ColumnInfo(name = "action")
    val action: String,

    @ColumnInfo(name = "principal_id")
    val principalId: String,

    @ColumnInfo(name = "integrity_hash")
    val integrityHash: String,

    @ColumnInfo(name = "previous_hash")
    val previousHash: String
)

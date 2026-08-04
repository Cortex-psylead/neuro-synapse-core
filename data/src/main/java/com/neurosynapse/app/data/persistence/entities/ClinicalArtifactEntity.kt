package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "clinical_artifacts",
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
data class ClinicalArtifactEntity(
    @PrimaryKey
    @ColumnInfo(name = "artifact_id")
    val artifactId: String,

    @ColumnInfo(name = "session_id")
    val sessionId: String,

    @ColumnInfo(name = "mime_type")
    val mimeType: String,

    @ColumnInfo(name = "file_path")
    val filePath: String,

    @ColumnInfo(name = "created_at_utc")
    val createdAtUtc: String,

    @ColumnInfo(name = "integrity_hash")
    val integrityHash: String
)

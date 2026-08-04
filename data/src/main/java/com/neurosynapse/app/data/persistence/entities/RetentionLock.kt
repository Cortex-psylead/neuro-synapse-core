package com.neurosynapse.app.data.persistence.entities

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Tabla de control para bypass de triggers append-only.
 * Solo debe contener una fila con ID = 1.
 */
@Entity(tableName = "retention_lock")
data class RetentionLock(
    @PrimaryKey 
    val id: Int = 1,
    
    @ColumnInfo(name = "bypass_enabled")
    val bypassEnabled: Boolean = false
)
package com.neurosynapse.app.data.persistence.dao

import androidx.room.*
import com.neurosynapse.app.data.persistence.entities.ClinicalArtifactEntity

@Dao
interface ClinicalArtifactDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertArtifact(artifact: ClinicalArtifactEntity): Long

    @Query("SELECT * FROM clinical_artifacts WHERE session_id = :sessionId")
    suspend fun getArtifactsForSession(sessionId: String): List<ClinicalArtifactEntity>
}

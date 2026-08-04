package com.neurosynapse.app.data.persistence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.neurosynapse.app.data.persistence.dao.*
import com.neurosynapse.app.data.persistence.entities.*
import com.neurosynapse.app.data.security.NeuroSynapseKeyManager
import net.sqlcipher.database.SupportFactory
import javax.crypto.Mac

@Database(
    entities = [
        ClinicalSessionEntity::class,
        AuditLogEntryEntity::class,
        RetentionLock::class,
        ClinicalArtifactEntity::class
    ],
    version = 5,
    exportSchema = true
)
abstract class NeuroSynapseDatabase : RoomDatabase() {

    abstract fun clinicalSessionDao(): ClinicalSessionDao
    abstract fun auditLogDao(): AuditLogDao
    abstract fun clinicalArtifactDao(): ClinicalArtifactDao

    companion object {
        @Volatile
        private var INSTANCE: NeuroSynapseDatabase? = null

        fun getInstance(context: Context, unlockedMac: Mac): NeuroSynapseDatabase {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: buildDatabase(context, unlockedMac).also { INSTANCE = it }
            }
        }

        private fun buildDatabase(context: Context, unlockedMac: Mac): NeuroSynapseDatabase {
            val keyManager = NeuroSynapseKeyManager(context)
            val passphrase = keyManager.derivePassphraseWithUnlockedMac(unlockedMac)

            return try {
                val factory = SupportFactory(passphrase)
                Room.databaseBuilder(
                    context.applicationContext,
                    NeuroSynapseDatabase::class.java,
                    "ns_clinical_v5.db"
                )
                    .openHelperFactory(factory)
                    .fallbackToDestructiveMigration(dropAllTables = true)
                    .addCallback(object : RoomDatabase.Callback() {
                        override fun onCreate(db: SupportSQLiteDatabase) {
                            super.onCreate(db)
                            db.execSQL("INSERT OR IGNORE INTO retention_lock (id, bypass_enabled) VALUES (1, 0)")
                        }
                    })
                    .build()
            } finally {
                passphrase.fill(0)
            }
        }
    }
}

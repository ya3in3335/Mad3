package com.samtex.eidreader.data.history

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import kotlinx.coroutines.flow.Flow

/**
 * One stored scan. Only non-identifying summary columns are stored in clear; the full
 * (already redacted) report is encrypted with an Android Keystore key.
 */
@Entity(tableName = "scan_history")
class ScanHistoryEntity(
    @PrimaryKey val id: String,
    val timestampMs: Long,
    val cardType: String,
    val technologies: String,
    val signature: String?,
    val status: String,
    val encryptedReport: ByteArray,
)

@Dao
interface ScanHistoryDao {
    @Query("SELECT * FROM scan_history ORDER BY timestampMs DESC")
    fun observeAll(): Flow<List<ScanHistoryEntity>>

    @Query("SELECT * FROM scan_history WHERE id = :id")
    suspend fun get(id: String): ScanHistoryEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(entity: ScanHistoryEntity)

    @Query("DELETE FROM scan_history WHERE id NOT IN (SELECT id FROM scan_history ORDER BY timestampMs DESC LIMIT :keep)")
    suspend fun trim(keep: Int)

    @Query("DELETE FROM scan_history")
    suspend fun clear()
}

@Database(entities = [ScanHistoryEntity::class], version = 1, exportSchema = false)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun scanHistoryDao(): ScanHistoryDao
}

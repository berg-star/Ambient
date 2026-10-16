package com.ambient.app.data.history

import android.content.Context
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Transaction
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "environment_snapshots", indices = [Index("savedAtEpochMillis")])
data class SnapshotEntity(
    @PrimaryKey val id: String,
    val savedAtEpochMillis: Long,
    val fetchedAtEpochMillis: Long,
    val areaLabel: String,
    val wasCached: Boolean,
    val payload: String,
    val temperature: String?,
    val temperatureUnit: String?,
    val aqi: String?,
    val note: String,
)

/** List projections never read or decode the full metric payload. */
data class SnapshotSummaryRow(
    val id: String,
    val savedAtEpochMillis: Long,
    val fetchedAtEpochMillis: Long,
    val areaLabel: String,
    val wasCached: Boolean,
    val temperature: String?,
    val temperatureUnit: String?,
    val aqi: String?,
    val note: String,
)

@Dao
interface HistoryDao {
    @Query("SELECT id, savedAtEpochMillis, fetchedAtEpochMillis, areaLabel, wasCached, temperature, temperatureUnit, aqi, note FROM environment_snapshots ORDER BY savedAtEpochMillis DESC, id DESC")
    fun observeSummaries(): Flow<List<SnapshotSummaryRow>>
    @Query("SELECT * FROM environment_snapshots WHERE id = :id")
    fun observeRecord(id: String): Flow<SnapshotEntity?>
    @Query("SELECT * FROM environment_snapshots ORDER BY savedAtEpochMillis DESC, id DESC LIMIT :limit OFFSET :offset")
    suspend fun exportBatch(limit: Int, offset: Int): List<SnapshotEntity>

    /** One transaction keeps pagination consistent if records are saved, edited, or deleted during export. */
    @Transaction
    suspend fun visitForExport(consume: (SnapshotEntity) -> Unit): Int {
        val batchSize = 64
        var count = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = exportBatch(batchSize, count)
            for (row in batch) {
                currentCoroutineContext().ensureActive()
                consume(row)
                count++
            }
            if (batch.size < batchSize) return count
        }
    }
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insert(entity: SnapshotEntity): Long
    @Query("UPDATE environment_snapshots SET note = :note WHERE id = :id")
    suspend fun updateNote(id: String, note: String): Int
    @Query("DELETE FROM environment_snapshots WHERE id = :id")
    suspend fun delete(id: String): Int
}

@Database(entities = [SnapshotEntity::class], version = 1, exportSchema = true)
abstract class HistoryDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    companion object {
        @Volatile private var instance: HistoryDatabase? = null
        fun get(context: Context): HistoryDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, HistoryDatabase::class.java, "ambient-history.db")
                .build().also { instance = it }
        }
    }
}

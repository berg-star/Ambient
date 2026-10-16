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
    @Query("SELECT DISTINCT areaLabel FROM environment_snapshots ORDER BY areaLabel ASC")
    fun observeAreas(): Flow<List<String>>

    @Query("SELECT id, savedAtEpochMillis, fetchedAtEpochMillis, areaLabel, wasCached, temperature, temperatureUnit, aqi, note FROM environment_snapshots WHERE (:areaLabel IS NULL OR areaLabel = :areaLabel) AND (:fromInclusive IS NULL OR fetchedAtEpochMillis >= :fromInclusive) AND (:untilExclusive IS NULL OR fetchedAtEpochMillis < :untilExclusive) ORDER BY savedAtEpochMillis DESC, id DESC")
    fun observeFilteredSummaries(areaLabel: String?, fromInclusive: Long?, untilExclusive: Long?): Flow<List<SnapshotSummaryRow>>

    // This is an invalidation trigger, not the trend itself. Do not distinctUntilChanged: a row can
    // change while the count remains the same; the consumer must re-read its consistent snapshot.
    @Query("SELECT COUNT(*) FROM environment_snapshots WHERE (:areaLabel IS NULL OR areaLabel = :areaLabel) AND (:fromInclusive IS NULL OR fetchedAtEpochMillis >= :fromInclusive) AND (:untilExclusive IS NULL OR fetchedAtEpochMillis < :untilExclusive)")
    fun observeTrendChanges(areaLabel: String?, fromInclusive: Long?, untilExclusive: Long?): Flow<Int>

    @Query("SELECT * FROM environment_snapshots WHERE (:areaLabel IS NULL OR areaLabel = :areaLabel) AND (:fromInclusive IS NULL OR fetchedAtEpochMillis >= :fromInclusive) AND (:untilExclusive IS NULL OR fetchedAtEpochMillis < :untilExclusive) ORDER BY fetchedAtEpochMillis DESC, savedAtEpochMillis ASC, id ASC LIMIT :limit OFFSET :offset")
    suspend fun trendBatch(areaLabel: String?, fromInclusive: Long?, untilExclusive: Long?, limit: Int, offset: Int): List<SnapshotEntity>

    /** Read one consistent set without retaining all decoded payloads in memory. */
    @Transaction
    suspend fun visitForTrend(areaLabel: String?, fromInclusive: Long?, untilExclusive: Long?, consume: (SnapshotEntity) -> Unit) {
        val batchSize = 64
        var offset = 0
        while (true) {
            currentCoroutineContext().ensureActive()
            val batch = trendBatch(areaLabel, fromInclusive, untilExclusive, batchSize, offset)
            for (row in batch) {
                currentCoroutineContext().ensureActive()
                consume(row)
            }
            if (batch.size < batchSize) return
            offset += batch.size
        }
    }
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

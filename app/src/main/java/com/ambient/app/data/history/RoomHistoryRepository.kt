package com.ambient.app.data.history

import android.content.Context
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.SnapshotPolicy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant

class RoomHistoryRepository internal constructor(
    private val daoFactory: () -> HistoryDao,
    private val clock: Clock = Clock.systemUTC(),
) : HistoryRepository {
    constructor(context: Context) : this({ HistoryDatabase.get(context.applicationContext).historyDao() })

    override fun observeRecords(): Flow<List<RecordSummary>> = flow {
        emitAll(daoFactory().observeSummaries().map { rows -> rows.map { row ->
            RecordSummary(row.id, row.areaLabel, Instant.ofEpochMilli(row.savedAtEpochMillis),
                Instant.ofEpochMilli(row.fetchedAtEpochMillis), row.wasCached, row.temperature, row.temperatureUnit, row.aqi, row.note)
        } })
    }.flowOn(Dispatchers.IO)

    override fun observeRecord(id: String): Flow<EnvironmentRecord?> = flow {
        emitAll(daoFactory().observeRecord(id).map { row -> row?.let {
            EnvironmentRecord(it.id, Instant.ofEpochMilli(it.savedAtEpochMillis), it.wasCached, SnapshotCodec.decode(it.payload), it.note)
        } })
    }.flowOn(Dispatchers.IO)

    override suspend fun visitRecordsForExport(consume: (EnvironmentRecord) -> Unit): Int = withContext(Dispatchers.IO) {
        daoFactory().visitForExport { row ->
            consume(EnvironmentRecord(row.id, Instant.ofEpochMilli(row.savedAtEpochMillis), row.wasCached,
                SnapshotCodec.decode(row.payload), row.note))
        }
    }

    override suspend fun save(draft: SnapshotDraft, note: String): String = withContext(Dispatchers.IO) {
        SnapshotPolicy.validate(draft.snapshot)
        val normalized = SnapshotPolicy.normalizeNote(note)
        require(draft.id.isNotBlank())
        val temperature = draft.snapshot.metrics.firstOrNull { it.id == "temperature" }
        val aqi = draft.snapshot.metrics.firstOrNull { it.id == "aqi" }
        // One row is atomic. A retry with the same frozen draft cannot create a duplicate or overwrite its data.
        daoFactory().insert(SnapshotEntity(draft.id, clock.instant().toEpochMilli(), draft.snapshot.updatedAt.toEpochMilli(),
            draft.snapshot.areaLabel, draft.wasCached, SnapshotCodec.encode(draft.snapshot),
            temperature?.value, temperature?.unit, aqi?.value, normalized))
        draft.id
    }
    override suspend fun updateNote(id: String, note: String) = withContext(Dispatchers.IO) {
        if (daoFactory().updateNote(id, SnapshotPolicy.normalizeNote(note)) != 1)
            throw HistoryException("这条记录已不存在，请返回列表。")
    }
    override suspend fun delete(id: String) = withContext(Dispatchers.IO) {
        daoFactory().delete(id)
        Unit
    }
}

package com.ambient.app.data.history

import android.content.Context
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryFilter
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.HistoryTrend
import com.ambient.app.domain.history.HistoryTrendAccumulator
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.SnapshotPolicy
import com.ambient.app.domain.history.TrendMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.withContext
import java.time.Clock
import java.time.Instant
import java.time.ZoneId

class RoomHistoryRepository internal constructor(
    private val daoFactory: () -> HistoryDao,
    private val clock: Clock = Clock.systemUTC(),
) : HistoryRepository {
    constructor(context: Context) : this({ HistoryDatabase.get(context.applicationContext).historyDao() })

    override fun observeRecords(): Flow<List<RecordSummary>> = flow {
        emitAll(daoFactory().observeSummaries().map { rows -> rows.map { it.toSummary() } })
    }.flowOn(Dispatchers.IO)

    override fun observeAreas(): Flow<List<String>> = flow {
        emitAll(daoFactory().observeAreas())
    }.flowOn(Dispatchers.IO)

    override fun observeFilteredRecords(filter: HistoryFilter, zone: ZoneId): Flow<List<RecordSummary>> = flow {
        val bounds = filter.bounds(zone)
        emitAll(daoFactory().observeFilteredSummaries(filter.areaLabel, bounds.fromInclusive?.toEpochMilli(),
            bounds.untilExclusive?.toEpochMilli()).map { rows -> rows.map { it.toSummary() } })
    }.flowOn(Dispatchers.IO)

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeTrend(filter: HistoryFilter, metric: TrendMetric, zone: ZoneId): Flow<HistoryTrend> = flow {
        val dao = daoFactory()
        val bounds = filter.bounds(zone)
        val from = bounds.fromInclusive?.toEpochMilli()
        val until = bounds.untilExclusive?.toEpochMilli()
        emitAll(dao.observeTrendChanges(filter.areaLabel, from, until).mapLatest {
            val accumulator = HistoryTrendAccumulator(filter, metric, zone)
            dao.visitForTrend(filter.areaLabel, from, until) { row -> accumulator.add(row.toRecord()) }
            accumulator.build()
        })
    }.flowOn(Dispatchers.IO)

    override fun observeRecord(id: String): Flow<EnvironmentRecord?> = flow {
        emitAll(daoFactory().observeRecord(id).map { row -> row?.toRecord() })
    }.flowOn(Dispatchers.IO)

    override suspend fun visitRecordsForExport(consume: (EnvironmentRecord) -> Unit): Int = withContext(Dispatchers.IO) {
        daoFactory().visitForExport { row ->
            consume(row.toRecord())
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

    private fun SnapshotSummaryRow.toSummary() = RecordSummary(id, areaLabel, Instant.ofEpochMilli(savedAtEpochMillis),
        Instant.ofEpochMilli(fetchedAtEpochMillis), wasCached, temperature, temperatureUnit, aqi, note)

    private fun SnapshotEntity.toRecord() = EnvironmentRecord(id, Instant.ofEpochMilli(savedAtEpochMillis), wasCached,
        SnapshotCodec.decode(payload), note)
}

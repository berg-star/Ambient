package com.ambient.app.ui.history

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.HistoryFilter
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.HistoryTrend
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.TrendMetric
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

/** Exercises the real Android main dispatcher and selection cancellation, without touching the app database. */
@RunWith(AndroidJUnit4::class)
class HistoryAnalysisViewModelTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val day = LocalDate.of(2026, 10, 16)
    private val a = summary("a", "区域 A")
    private val b = summary("b", "区域 B")

    @Test fun allRegionsRequireASelectionBeforeAnyTrendQueryAndExportKeepsAllRows() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a, b), listOf("区域 A", "区域 B"))
        repository.filtered(HistoryFilter(areaLabel = "区域 A")).value = Emission(listOf(a))
        withModel(repository) { model ->
            model.analysis.await { !it.loading && it.areaRequired }
            assertTrue(repository.trendRequests.isEmpty())
            assertEquals(2, model.allRecords.await { !it.loading }.records.size)
            onMain { model.selectArea("区域 A") }
            val analysis = model.analysis.await { !it.loading && it.trend?.areaLabel == "区域 A" }
            assertFalse(analysis.areaRequired)
            assertEquals(listOf(a), model.records.await { !it.loading && it.records == listOf(a) }.records)
            assertEquals(listOf(a, b), model.allRecords.value.records)
            assertTrue(repository.trendRequests.all { it.filter.areaLabel == "区域 A" })
        }
    }

    @Test fun aSingleRegionIsInferredAndMetricAndDateSelectionsReachIndependentQueries() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a), listOf("区域 A"))
        withModel(repository) { model ->
            model.analysis.await { !it.loading && it.trend?.areaLabel == "区域 A" }
            assertNull(model.filter.value.areaLabel)
            onMain {
                model.selectMetric(TrendMetric.PRESSURE)
                model.setDateRange(day.minusDays(6), day)
            }
            repository.latestTrendRequest.await { it?.filter == HistoryFilter("区域 A", day.minusDays(6), day) && it.metric == TrendMetric.PRESSURE }
            model.analysis.await { !it.loading && it.trend?.metric == TrendMetric.PRESSURE && it.appliedFilter == HistoryFilter(fromDate = day.minusDays(6), toDate = day) }
            val requested = repository.trendRequests.last()
            assertEquals(HistoryFilter("区域 A", day.minusDays(6), day), requested.filter)
            assertEquals(zone, repository.requestedZones.last())
            assertEquals(TrendMetric.PRESSURE, model.selectedMetric.value)
        }
    }

    @Test fun anEmptyFilteredResultIsNotAnErrorAndDoesNotDisableAccessToAllRecords() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a), listOf("区域 A"))
        val unmatched = HistoryFilter(fromDate = day.plusDays(1), toDate = day.plusDays(1))
        withModel(repository) { model ->
            model.allRecords.await { !it.loading && it.records.isNotEmpty() }
            onMain { model.setDateRange(unmatched.fromDate, unmatched.toDate) }
            val empty = model.records.await { !it.loading && it.records.isEmpty() && it.appliedFilter == unmatched }
            assertNull(empty.error)
            assertEquals(listOf(a), model.allRecords.value.records)
            model.analysis.await { !it.loading && it.trend?.points?.isEmpty() == true && it.appliedFilter == unmatched }
            assertTrue(repository.recordRequests.contains(unmatched))
        }
    }

    @Test fun switchingRegionsCancelsThePreviousFlowAndLateOldValuesCannotReplaceTheSelection() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a, b), listOf("区域 A", "区域 B"))
        val filterA = HistoryFilter(areaLabel = "区域 A")
        val filterB = HistoryFilter(areaLabel = "区域 B")
        repository.filtered(filterA).value = Emission(listOf(a))
        repository.filtered(filterB).value = Emission(listOf(b))
        withModel(repository) { model ->
            onMain { model.selectArea("区域 A") }
            model.records.await { !it.loading && it.records == listOf(a) }
            model.analysis.await { !it.loading && it.trend?.areaLabel == "区域 A" }
            onMain { model.selectArea("区域 B") }
            model.records.await { !it.loading && it.records == listOf(b) }
            model.analysis.await { !it.loading && it.trend?.areaLabel == "区域 B" }
            assertTrue(repository.cancelledRecordQueries.contains(filterA))
            assertTrue(repository.cancelledTrendQueries.any { it.filter == filterA })
            repository.filtered(filterA).value = Emission(listOf(a.copy(note = "late old value")))
            repository.trend(filterA, TrendMetric.TEMPERATURE).value = Emission(HistoryTrend("区域 A", TrendMetric.TEMPERATURE, emptyList(), 99, false))
            // A further value from the active flow is a deterministic delivery barrier, without sleep-based assertions.
            val updatedB = b.copy(note = "active value")
            repository.filtered(filterB).value = Emission(listOf(updatedB))
            model.records.await { !it.loading && it.records == listOf(updatedB) }
            assertEquals("区域 B", model.analysis.value.trend?.areaLabel)
            assertEquals(0, model.analysis.value.trend?.duplicateCount)
        }
    }

    @Test fun anInvalidDateRangeLeavesTheCurrentFilterIntactAndResetRestoresAllDatesAndRegions() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a), listOf("区域 A"))
        withModel(repository) { model ->
            onMain { model.selectArea("区域 A"); model.setDateRange(day, day) }
            val original = model.filter.value
            onMain { assertFalse(model.setDateRange(day.plusDays(1), day)) }
            assertEquals(original, model.filter.value)
            assertEquals("开始日期不能晚于结束日期。", model.actions.value.error)
            onMain { model.clearFilter() }
            assertEquals(HistoryFilter(), model.filter.value)
            assertNull(model.actions.value.error)
            assertEquals(zone, model.zoneId)
            onMain { assertTrue(model.setDateRange(null, day)) }
            assertEquals(HistoryFilter(toDate = day), model.filter.value)
        }
    }

    @Test fun failedQueriesHaveVisibleErrorsAndRetryRecoversWithoutDeletingOrChangingRecords() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a), listOf("区域 A"))
        val filter = HistoryFilter(areaLabel = "区域 A")
        repository.filtered(filter).value = Emission(emptyList(), fail = true)
        repository.trend(filter, TrendMetric.TEMPERATURE).value = Emission(HistoryTrend("区域 A", TrendMetric.TEMPERATURE, emptyList(), 0, false), fail = true)
        withModel(repository) { model ->
            onMain { model.selectArea("区域 A") }
            assertNotNull(model.records.await { !it.loading && it.error != null }.error)
            assertNotNull(model.analysis.await { !it.loading && it.error != null }.error)
            assertEquals(listOf(a), model.allRecords.await { !it.loading }.records)
            repository.filtered(filter).value = Emission(listOf(a))
            repository.trend(filter, TrendMetric.TEMPERATURE).value = Emission(HistoryTrend("区域 A", TrendMetric.TEMPERATURE, emptyList(), 0, false))
            onMain { model.retry() }
            assertNull(model.records.await { !it.loading && it.records == listOf(a) }.error)
            assertNull(model.analysis.await { !it.loading && it.trend?.areaLabel == "区域 A" }.error)
            assertEquals(0, repository.mutations)
        }
    }

    @Test fun aRegionListFailureIsReportedAndCanBeRetriedRatherThanPretendingThereAreNoRecords() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a), listOf("区域 A"))
        repository.areaRows.value = Emission(emptyList(), fail = true)
        withModel(repository) { model ->
            assertNotNull(model.areas.await { !it.loading && it.error != null }.error)
            assertNotNull(model.analysis.await { !it.loading && it.error != null }.error)
            assertTrue(repository.trendRequests.isEmpty())
            repository.areaRows.value = Emission(listOf("区域 A"))
            onMain { model.retry() }
            assertEquals(listOf("区域 A"), model.areas.await { !it.loading && it.error == null }.areas)
            assertEquals("区域 A", model.analysis.await { !it.loading && it.trend != null }.trend?.areaLabel)
            assertEquals(0, repository.mutations)
        }
    }

    @Test fun comparisonReadsExactlyTwoSnapshotsAndRetainsSelectionAcrossFilters() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a, b), listOf("区域 A", "区域 B"))
        withModel(repository) { model ->
            onMain { model.toggleComparison(a.id) }
            assertEquals(listOf(a.id), model.comparisonIds.value)
            assertTrue(repository.detailRequests.isEmpty())
            onMain { model.toggleComparison(b.id) }
            val pair = model.comparison.await { !it.loading && it.records.size == 2 }.records
            assertEquals(listOf("区域 A", "区域 B"), pair.map { it.snapshot.areaLabel })
            assertEquals(listOf(a.id, b.id), pair.map { it.id })
            onMain { model.selectArea("区域 A"); model.setDateRange(day, day) }
            assertEquals(listOf(a.id, b.id), model.comparisonIds.value)
            onMain { model.toggleComparison("third") }
            assertEquals(listOf(a.id, b.id), model.comparisonIds.value)
            assertEquals("最多对比两条记录，请先取消一条。", model.actions.value.error)
            onMain { model.toggleComparison(a.id) }
            assertEquals(listOf(b.id), model.comparisonIds.value)
            model.comparison.await { !it.loading && it.records.isEmpty() && it.error == null }
            onMain { model.clearComparison() }
            assertTrue(model.comparisonIds.value.isEmpty())
            assertNull(model.actions.value.error)
        }
    }

    @Test fun missingComparedRecordClearsStalePairAndDeletingSelectedRecordRemovesItsSelection() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a, b), listOf("区域 A", "区域 B"))
        withModel(repository) { model ->
            onMain { model.toggleComparison(a.id); model.toggleComparison(b.id) }
            model.comparison.await { !it.loading && it.records.size == 2 }
            repository.record(b.id).value = Emission(null)
            val missing = model.comparison.await { it.error != null }
            assertTrue(missing.records.isEmpty())
            assertFalse(missing.loading)
            repository.record(b.id).value = Emission(repository.asRecord(b))
            model.comparison.await { !it.loading && it.records.size == 2 }
            onMain { model.delete(a.id) }
            model.actions.await { !it.busy && it.message == "这条记录已删除。" }
            assertEquals(listOf(b.id), model.comparisonIds.value)
            model.comparison.await { !it.loading && it.records.isEmpty() }
        }
    }

    @Test fun comparisonReadFailureIsVisibleAndRetryCanRecoverThePairWithoutMutation() = runBlocking {
        val repository = FakeHistoryRepository(listOf(a, b), listOf("区域 A", "区域 B"))
        repository.record(b.id).value = Emission(null, fail = true)
        withModel(repository) { model ->
            onMain { model.toggleComparison(a.id); model.toggleComparison(b.id) }
            val failed = model.comparison.await { it.error != null }
            assertTrue(failed.records.isEmpty())
            repository.record(b.id).value = Emission(repository.asRecord(b))
            onMain { model.retry() }
            assertEquals(listOf(a.id, b.id), model.comparison.await { !it.loading && it.records.size == 2 }.records.map { it.id })
            assertEquals(0, repository.mutations)
        }
    }

    private suspend fun withModel(repository: FakeHistoryRepository, block: suspend (HistoryViewModel) -> Unit) = coroutineScope {
        lateinit var model: HistoryViewModel
        onMain { model = HistoryViewModel(repository, zone) }
        val observers = listOf(model.records, model.allRecords, model.areas, model.analysis, model.comparison).map { state ->
            launch(Dispatchers.Unconfined) { state.collect() }
        }
        try { block(model) }
        finally { observers.forEach { it.cancel() }; model.viewModelScope.cancel() }
    }

    private suspend fun <T> StateFlow<T>.await(predicate: (T) -> Boolean): T = withTimeout(10000) { first(predicate) }
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    private fun summary(id: String, area: String) = RecordSummary(id, area, Instant.parse("2026-10-16T08:00:00Z"),
        Instant.parse("2026-10-16T07:00:00Z"), false, "20", "°C", null, "")

    private data class Emission<T>(val value: T, val fail: Boolean = false)
    private data class TrendRequest(val filter: HistoryFilter, val metric: TrendMetric)
    private class FakeHistoryRepository(rows: List<RecordSummary>, areas: List<String>) : HistoryRepository {
        val all = MutableStateFlow(rows)
        val areaRows = MutableStateFlow(Emission(areas))
        val recordRequests = CopyOnWriteArrayList<HistoryFilter>()
        val trendRequests = CopyOnWriteArrayList<TrendRequest>()
        val latestTrendRequest = MutableStateFlow<TrendRequest?>(null)
        val requestedZones = CopyOnWriteArrayList<ZoneId>()
        val cancelledRecordQueries = CopyOnWriteArrayList<HistoryFilter>()
        val cancelledTrendQueries = CopyOnWriteArrayList<TrendRequest>()
        val detailRequests = CopyOnWriteArrayList<String>()
        private val detailRows = ConcurrentHashMap<String, MutableStateFlow<Emission<EnvironmentRecord?>>>()
        private val filteredRows = ConcurrentHashMap<HistoryFilter, MutableStateFlow<Emission<List<RecordSummary>>>>()
        private val trends = ConcurrentHashMap<TrendRequest, MutableStateFlow<Emission<HistoryTrend>>>()
        var mutations = 0
        init {
            filtered(HistoryFilter()).value = Emission(rows)
            rows.forEach { record(it.id).value = Emission(asRecord(it)) }
        }
        fun record(id: String) = detailRows.computeIfAbsent(id) { MutableStateFlow(Emission(null)) }
        fun asRecord(row: RecordSummary) = EnvironmentRecord(row.id, row.savedAt, row.wasCached,
            EnvironmentSnapshot(row.areaLabel, row.fetchedAt, emptyList()), row.note)
        fun filtered(filter: HistoryFilter) = filteredRows.computeIfAbsent(filter) { MutableStateFlow(Emission(emptyList())) }
        fun trend(filter: HistoryFilter, metric: TrendMetric) = trends.computeIfAbsent(TrendRequest(filter, metric)) {
            MutableStateFlow(Emission(HistoryTrend(filter.areaLabel!!, metric, emptyList(), 0, false)))
        }
        override fun observeRecords(): Flow<List<RecordSummary>> = all
        override fun observeAreas(): Flow<List<String>> = areaRows.map { if (it.fail) throw IOException("synthetic read failure") else it.value }
        override fun observeFilteredRecords(filter: HistoryFilter, zone: ZoneId): Flow<List<RecordSummary>> = flow {
            recordRequests += filter; requestedZones += zone
            try { emitAll(filtered(filter).map { if (it.fail) throw IOException("synthetic read failure") else it.value }) }
            finally { cancelledRecordQueries += filter }
        }
        override fun observeTrend(filter: HistoryFilter, metric: TrendMetric, zone: ZoneId): Flow<HistoryTrend> = flow {
            val request = TrendRequest(filter, metric)
            trendRequests += request; requestedZones += zone
            latestTrendRequest.value = request
            try { emitAll(trend(filter, metric).map { if (it.fail) throw IOException("synthetic read failure") else it.value }) }
            finally { cancelledTrendQueries += request }
        }
        override fun observeRecord(id: String): Flow<EnvironmentRecord?> = flow {
            detailRequests += id
            emitAll(record(id).map { if (it.fail) throw IOException("synthetic read failure") else it.value })
        }
        override suspend fun visitRecordsForExport(consume: (EnvironmentRecord) -> Unit): Int = 0
        override suspend fun save(draft: SnapshotDraft, note: String): String { mutations++; return draft.id }
        override suspend fun updateNote(id: String, note: String) { mutations++ }
        override suspend fun delete(id: String) { mutations++ }
    }
}

package com.ambient.app.data.history

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryFilter
import com.ambient.app.domain.history.HistoryTrendAccumulator
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.TrendMetric
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HistoryAnalysisReadTest {
    private lateinit var database: HistoryDatabase
    private lateinit var repository: RoomHistoryRepository
    private val day = LocalDate.of(2026, 10, 16)
    private val zone = ZoneId.of("Asia/Shanghai")
    private val dayStart = day.atStartOfDay(zone).toInstant()
    private val nextStart = day.plusDays(1).atStartOfDay(zone).toInstant()
    private class MutableClock(var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    // Saves deliberately happen much later than the measurements used by the date filter.
    private val clock = MutableClock(Instant.parse("2026-11-20T09:00:00Z"))

    @Before fun create() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        repository = RoomHistoryRepository({ database.historyDao() }, clock)
    }

    @After fun cleanup() { database.close() }

    private suspend fun save(
        id: String,
        area: String = "测试区域 A · 非用户位置",
        fetchedAt: Instant = dayStart.plusSeconds(3600),
        cached: Boolean = false,
        temperature: String = "20.0",
        unit: String = "°C",
        note: String = id,
    ): EnvironmentRecord {
        val snapshot = EnvironmentSnapshot(area, fetchedAt, listOf(
            EnvironmentMetric("temperature", "室外温度", temperature, unit, DataSource.REGIONAL_API, "测试区域数据"),
            EnvironmentMetric("humidity", "相对湿度", "50", "%", DataSource.REGIONAL_API, "测试湿度"),
            EnvironmentMetric("pressure", "大气压", "1010", "hPa", DataSource.REGIONAL_API, "测试气压"),
            EnvironmentMetric("aqi", "空气质量", "—", "AQI", DataSource.REGIONAL_API, "空气数据缺失"),
        ), isDemo = false)
        val record = EnvironmentRecord(id, clock.time, cached, snapshot, note)
        repository.save(SnapshotDraft(id, snapshot, cached), note)
        clock.time = clock.time.plusSeconds(1)
        return record
    }

    private fun expected(filter: HistoryFilter, metric: TrendMetric, records: List<EnvironmentRecord>) =
        HistoryTrendAccumulator(filter, metric, zone).also { accumulator ->
            records.sortedWith(compareByDescending<EnvironmentRecord> { it.snapshot.updatedAt.toEpochMilli() }
                .thenBy { it.savedAt }.thenBy { it.id }).forEach { accumulator.add(it) }
        }.build()

    @Test fun emptyDatabaseProducesEmptyAreasFilteredListAndTrend() = runBlocking {
        assertTrue(repository.observeAreas().first().isEmpty())
        assertTrue(repository.observeFilteredRecords(HistoryFilter(), zone).first().isEmpty())
        val selectedArea = HistoryFilter(areaLabel = "测试区域")
        assertEquals(expected(selectedArea, TrendMetric.TEMPERATURE, emptyList()),
            repository.observeTrend(selectedArea, TrendMetric.TEMPERATURE, zone).first())
        assertTrue(repository.observeFilteredRecords(HistoryFilter(fromDate = day, toDate = day), zone).first().isEmpty())
    }

    @Test fun localDayIncludesBothEdgesAndUsesFetchedTimeRatherThanLaterSaveTime() = runBlocking {
        save("before", fetchedAt = dayStart.minusMillis(1))
        val atStart = save("start", fetchedAt = dayStart)
        val beforeEnd = save("last", fetchedAt = nextStart.minusMillis(1), cached = true)
        save("next-day", fetchedAt = nextStart)
        val filter = HistoryFilter(areaLabel = atStart.snapshot.areaLabel, fromDate = day, toDate = day)
        val rows = repository.observeFilteredRecords(filter, zone).first()
        assertEquals(listOf(beforeEnd.id, atStart.id), rows.map { it.id })
        assertTrue(rows.all { it.savedAt.isAfter(nextStart) })
        assertEquals(listOf(beforeEnd.snapshot.updatedAt, atStart.snapshot.updatedAt), rows.map { it.fetchedAt })
        assertEquals(expected(filter, TrendMetric.TEMPERATURE, listOf(atStart, beforeEnd)),
            repository.observeTrend(filter, TrendMetric.TEMPERATURE, zone).first())
    }

    @Test fun exactAreaAndOpenDateBoundsDoNotMixRegionsOrDays() = runBlocking {
        val area = "测试 ' A · 非用户位置"
        val earlier = save("earlier", area, dayStart.minusSeconds(60))
        val included = save("included", area, dayStart.plusSeconds(60))
        val later = save("later", area, nextStart.plusSeconds(60))
        save("other-area", "$area extra", dayStart.plusSeconds(60))
        assertEquals(listOf(later.id, included.id, earlier.id),
            repository.observeFilteredRecords(HistoryFilter(areaLabel = area), zone).first().map { it.id })
        assertEquals(listOf(later.id, included.id),
            repository.observeFilteredRecords(HistoryFilter(areaLabel = area, fromDate = day), zone).first().map { it.id })
        assertEquals(listOf(included.id, earlier.id),
            repository.observeFilteredRecords(HistoryFilter(areaLabel = area, toDate = day), zone).first().map { it.id })
        assertTrue(repository.observeFilteredRecords(HistoryFilter(areaLabel = ""), zone).first().isEmpty())
        assertEquals(2, repository.observeFilteredRecords(HistoryFilter(fromDate = day, toDate = day), zone).first().size)
    }

    @Test fun areasAreDistinctSortedAndListProjectionDoesNotDecodePayload() = runBlocking {
        save("z", area = "Z area")
        save("a-first", area = "A area")
        save("a-second", area = "A area")
        // A malformed payload remains visible in summaries and area choices; only detail/trend reads decode it.
        database.historyDao().insert(SnapshotEntity("corrupt", clock.time.toEpochMilli(), dayStart.toEpochMilli(),
            "M area", false, "not-json", "12", "°C", null, "kept"))
        assertEquals(listOf("A area", "M area", "Z area"), repository.observeAreas().first())
        assertEquals("kept", repository.observeFilteredRecords(HistoryFilter(areaLabel = "M area"), zone).first().single().note)
        assertEquals(4, repository.observeRecords().first().size)
    }

    @Test fun trendReadsMultiplePagesWithMetricGapsUnitChangesAndRepeatedCachedAcquisition() = runBlocking {
        val area = "测试区域 A · 非用户位置"
        val records = (0 until 137).map { index ->
            save("record-${index.toString().padStart(3, '0')}", area,
                dayStart.plusSeconds((index / 2).toLong() * 30), cached = index % 2 == 1,
                temperature = if (index % 7 == 0) "—" else (10 + index / 10.0).toString(),
                unit = if (index % 11 == 0) "K" else "°C")
        }
        save("other", area = "另一区域 · 非用户位置", temperature = "99")
        val filter = HistoryFilter(area, day, day)
        for (metric in TrendMetric.entries) {
            assertEquals(expected(filter, metric, records), repository.observeTrend(filter, metric, zone).first())
        }
        assertEquals(137, repository.observeFilteredRecords(filter, zone).first().size)
        assertEquals(138, repository.observeRecords().first().size)
    }

    @Test fun sameCountNoteEditInvalidatesTrendAndDeletionUpdatesBothFlows() = runBlocking {
        val record = save("first")
        val filter = HistoryFilter(areaLabel = record.snapshot.areaLabel)
        val events = Channel<com.ambient.app.domain.history.HistoryTrend>(Channel.UNLIMITED)
        val collector = launch(Dispatchers.Default) {
            repository.observeTrend(filter, TrendMetric.TEMPERATURE, zone).collect { events.send(it) }
        }
        try {
            assertEquals(expected(filter, TrendMetric.TEMPERATURE, listOf(record)), withTimeout(10000) { events.receive() })
            repository.updateNote(record.id, "updated-note")
            val updated = expected(filter, TrendMetric.TEMPERATURE, listOf(record.copy(note = "updated-note")))
            withTimeout(10000) {
                while (events.receive() != updated) { /* Ignore a permitted intermediate invalidation. */ }
            }
            assertEquals("updated-note", repository.observeFilteredRecords(filter, zone).first().single().note)
            repository.delete(record.id)
            val empty = expected(filter, TrendMetric.TEMPERATURE, emptyList())
            withTimeout(10000) { while (events.receive() != empty) { } }
            assertTrue(repository.observeFilteredRecords(filter, zone).first().isEmpty())
            assertTrue(repository.observeAreas().first().isEmpty())
        } finally { collector.cancel(); collector.join(); events.close() }
    }

    @Test fun cancellingPagedTrendReadStopsBeforeAnotherRowAndLeavesHistoryIntact() = runBlocking {
        repeat(70) { save("record-$it", fetchedAt = dayStart.plusSeconds(it.toLong())) }
        val entered = CompletableDeferred<Unit>()
        val release = CountDownLatch(1)
        var delivered = 0
        val read = launch(Dispatchers.Default) {
            database.historyDao().visitForTrend(null, null, null) {
                if (++delivered == 1) {
                    entered.complete(Unit)
                    check(release.await(5, TimeUnit.SECONDS))
                }
            }
        }
        try {
            withTimeout(10000) { entered.await() }
            read.cancel()
        } finally { release.countDown() }
        withTimeout(10000) { read.join() }
        assertTrue(read.isCancelled)
        assertEquals(1, delivered)
        assertEquals(70, repository.observeRecords().first().size)
    }

    @Test fun corruptTrendPayloadPropagatesFailureWithoutErasingStoredRows() = runBlocking {
        database.historyDao().insert(SnapshotEntity("corrupt", clock.time.toEpochMilli(), dayStart.toEpochMilli(),
            "测试区域", false, "not-json", "12", "°C", null, "kept"))
        try {
            repository.observeTrend(HistoryFilter(areaLabel = "测试区域"), TrendMetric.TEMPERATURE, zone).first()
            fail("Corrupt trend payload was silently accepted")
        } catch (expected: Exception) {
            assertFalse(expected is kotlinx.coroutines.CancellationException)
        }
        assertEquals("kept", repository.observeRecords().first().single().note)
    }

    @Test fun filteringAndTrendReadsNeverRestrictOrMutateCsvExport() = runBlocking {
        val first = save("first", area = "A area", fetchedAt = dayStart)
        val second = save("second", area = "B area", fetchedAt = dayStart.minusSeconds(60), cached = true)
        val third = save("third", area = "A area", fetchedAt = nextStart)
        val before = repository.observeRecords().first()
        val filter = HistoryFilter("A area", day, day)
        assertEquals(listOf(first.id), repository.observeFilteredRecords(filter, zone).first().map { it.id })
        repository.observeTrend(filter, TrendMetric.TEMPERATURE, zone).first()
        val exported = mutableListOf<EnvironmentRecord>()
        assertEquals(3, repository.visitRecordsForExport { exported += it })
        assertEquals(listOf(third, second, first), exported)
        assertEquals(before, repository.observeRecords().first())
    }
}

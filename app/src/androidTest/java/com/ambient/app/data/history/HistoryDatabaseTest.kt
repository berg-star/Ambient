package com.ambient.app.data.history

import android.content.Context
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.withPositioningHeight
import com.ambient.app.domain.history.HistoryCsvWriter
import java.io.StringWriter
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.ui.history.HistoryViewModel
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import androidx.lifecycle.viewModelScope
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class HistoryDatabaseTest {
    private lateinit var context: Context
    private lateinit var database: HistoryDatabase
    private lateinit var repository: RoomHistoryRepository
    private val fetchedAt = Instant.parse("2026-10-16T08:00:00.123456Z")
    private class MutableClock(var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val clock = MutableClock(Instant.parse("2026-10-16T08:10:00Z"))
    private var diskName: String? = null
    private val snapshot get() = EnvironmentSnapshot("测试区域 · 非用户位置", fetchedAt,
        listOf(
            EnvironmentMetric("temperature", "室外温度", "20.1", "°C", DataSource.REGIONAL_API, "测试区域数据"),
            EnvironmentMetric("humidity", "相对湿度", "60", "%", DataSource.REGIONAL_API, "测试湿度"),
            EnvironmentMetric("aqi", "空气质量 · 中国", "—", "AQI", DataSource.REGIONAL_API, "本次无空气数据"),
            EnvironmentMetric("windDirection", "风向", "东东北", "", DataSource.REGIONAL_API, "风的来向", 68.0),
        ), attributions = listOf("https://example.org/attribution"), notices = listOf("空气质量未更新"), isDemo = false)

    @Before fun create() {
        context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        repository = RoomHistoryRepository({ database.historyDao() }, clock)
    }
    @After fun cleanup() {
        database.close()
        diskName?.let { context.deleteDatabase(it) } // Only this test's random file, never ambient-history.db.
    }
    private fun draft(id: String = UUID.randomUUID().toString(), cached: Boolean = true) = SnapshotDraft(id, snapshot, cached)

    @Test fun positioningHeightKeepsItsOwnSourceAndFixTimeThroughRoomAndCsv() = runBlocking {
        val fixTime = fetchedAt.minusSeconds(120)
        val area = EnvironmentArea(snapshot.areaLabel, 30.0, 120.0, fromDevice = true, isMock = true,
            positioningHeightMeters = 123.4, verticalAccuracyMeters = 15.0, positioningHeightAtMillis = fixTime.toEpochMilli())
        val expected = snapshot.withPositioningHeight(area)
        val input = SnapshotDraft(UUID.randomUUID().toString(), expected, true)
        repository.save(input, "synthetic positioning height")
        val record = repository.observeRecord(input.id).first()!!
        assertEquals(expected, record.snapshot)
        val height = record.snapshot.metrics.first { it.id == "altitude" }
        assertEquals(DataSource.POSITIONING, height.source)
        assertTrue(height.isSimulated)
        assertTrue(height.description.contains(Instant.ofEpochMilli(fixTime.toEpochMilli()).toString()))
        assertEquals(fetchedAt, record.snapshot.updatedAt)
        val csv = StringWriter()
        repository.visitRecordsForExport { HistoryCsvWriter.writeRecord(csv, it) }
        assertTrue(csv.toString().contains("POSITIONING"))
        assertTrue(csv.toString().contains("模拟定位"))
    }

    @Test fun cachedSnapshotKeepsBothTimesSourcesMissingValuesAngleAndAttribution() = runBlocking {
        val input = draft()
        repository.save(input, "  Morning walk  ")
        val record = withTimeout(10000) { repository.observeRecord(input.id).first { it != null } }!!
        assertEquals(snapshot, record.snapshot)
        assertEquals(fetchedAt, record.snapshot.updatedAt)
        assertNull(record.snapshot.observedAt)
        assertEquals(clock.time, record.savedAt)
        assertTrue(record.wasCached)
        assertEquals("Morning walk", record.note)
        assertEquals("—", record.snapshot.metrics.first { it.id == "aqi" }.value)
        assertEquals(68.0, record.snapshot.metrics.first { it.id == "windDirection" }.windFromDegrees!!, 0.0)
    }

    @Test fun repeatedSaveOfOneDraftIsIdempotentAndDoesNotRewriteOriginalTimeOrNote() = runBlocking {
        val input = draft()
        repository.save(input, "first")
        val originalTime = clock.time
        clock.time = clock.time.plusSeconds(60)
        repository.save(input, "second")
        val summaries = withTimeout(10000) { repository.observeRecords().first { it.isNotEmpty() } }
        assertEquals(1, summaries.size)
        assertEquals(originalTime, summaries.first().savedAt)
        assertEquals("first", summaries.first().note)
    }

    @Test fun listOrdersBySaveTimeAndDeleteOnlyRemovesTargetRecord() = runBlocking {
        val older = draft(); repository.save(older, "old")
        clock.time = clock.time.plusSeconds(60)
        val newer = draft(cached = false); repository.save(newer, "new")
        val list = withTimeout(10000) { repository.observeRecords().first { it.size == 2 } }
        assertEquals(listOf(newer.id, older.id), list.map { it.id })
        assertFalse(list.first().wasCached)
        repository.delete(newer.id)
        assertNull(repository.observeRecord(newer.id).first())
        assertNotNull(repository.observeRecord(older.id).first())
        assertEquals(1, repository.observeRecords().first().size)
    }

    @Test fun noteEditsDoNotAlterSnapshotAndOverlongNoteLeavesOriginalUntouched() = runBlocking {
        val input = draft(); repository.save(input, "first")
        repository.updateNote(input.id, "updated")
        val updated = repository.observeRecord(input.id).first()!!
        assertEquals("updated", updated.note)
        assertEquals(snapshot, updated.snapshot)
        assertEquals(clock.time, updated.savedAt)
        try { repository.updateNote(input.id, "a".repeat(301)); fail("Long note accepted") } catch (_: HistoryException) { }
        assertEquals("updated", repository.observeRecord(input.id).first()!!.note)
        try { repository.updateNote("missing", "note"); fail("Missing record accepted") } catch (_: HistoryException) { }
    }

    @Test fun demoAndMixedDemoSnapshotsCannotEnterDatabase() = runBlocking {
        for (s in listOf(snapshot.copy(isDemo = true), snapshot.copy(metrics = snapshot.metrics + snapshot.metrics.first().copy(source = DataSource.DEMO)))) {
            try { repository.save(SnapshotDraft(UUID.randomUUID().toString(), s, false), ""); fail("Demo saved") }
            catch (_: HistoryException) { }
        }
        assertTrue(repository.observeRecords().first().isEmpty())
    }

    @Test fun diskDatabaseSurvivesCloseReopenWithoutNetworkOrDataLoss() = runBlocking {
        database.close()
        diskName = "ambient-history-test-${UUID.randomUUID()}.db"
        database = Room.databaseBuilder(context, HistoryDatabase::class.java, diskName!!).build()
        val input = draft(); repository.save(input, "persisted")
        database.close()
        database = Room.databaseBuilder(context, HistoryDatabase::class.java, diskName!!).build()
        val reloaded = repository.observeRecord(input.id).first()!!
        assertEquals(snapshot, reloaded.snapshot)
        assertEquals("persisted", reloaded.note)
        assertTrue(reloaded.wasCached)
    }

    @Test fun viewModelFreezesDisplayedDataAndIgnoresRepeatedConfirmation() = runBlocking {
        lateinit var model: HistoryViewModel
        val mutableMetrics = snapshot.metrics.toMutableList()
        val displayed = snapshot.copy(metrics = mutableMetrics)
        var id = ""
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = HistoryViewModel(repository)
            model.prepareSave(displayed, true)
            id = model.actions.value.draft!!.id
            mutableMetrics.clear()
            model.save("frozen")
            model.save("duplicate")
        }
        try {
            withTimeout(10000) { model.actions.first { !it.busy && it.draft == null && it.message != null } }
            val saved = repository.observeRecord(id).first()!!
            assertEquals(snapshot.metrics, saved.snapshot.metrics)
            assertEquals("frozen", saved.note)
            assertEquals(1, repository.observeRecords().first().size)
        } finally { model.viewModelScope.cancel() }
    }

    @Test fun failedSaveKeepsDraftForRetryAndOnlyReportsSuccessAfterWriting() = runBlocking {
        var failNext = true
        val unreliable = object : HistoryRepository by repository {
            override suspend fun save(draft: SnapshotDraft, note: String): String {
                if (failNext) throw IOException("Synthetic disk failure")
                return repository.save(draft, note)
            }
        }
        lateinit var model: HistoryViewModel
        InstrumentationRegistry.getInstrumentation().runOnMainSync {
            model = HistoryViewModel(unreliable); model.prepareSave(snapshot, false); model.save("retry")
        }
        try {
            val failed = withTimeout(10000) { model.actions.first { !it.busy && it.error != null } }
            assertNotNull(failed.draft)
            assertNull(failed.message)
            assertTrue(repository.observeRecords().first().isEmpty())
            failNext = false
            InstrumentationRegistry.getInstrumentation().runOnMainSync { model.save("retry") }
            withTimeout(10000) { model.actions.first { !it.busy && it.draft == null && it.message != null } }
            assertEquals(1, repository.observeRecords().first().size)
        } finally { model.viewModelScope.cancel() }
    }
}

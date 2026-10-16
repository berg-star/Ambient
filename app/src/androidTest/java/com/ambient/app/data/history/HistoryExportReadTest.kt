package com.ambient.app.data.history

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.SnapshotDraft
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
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
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

@RunWith(AndroidJUnit4::class)
class HistoryExportReadTest {
    private lateinit var database: HistoryDatabase
    private lateinit var repository: RoomHistoryRepository
    private val baseTime = Instant.parse("2026-10-16T08:10:00.123Z")
    private class MutableClock(var time: Instant) : Clock() {
        override fun instant(): Instant = time
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val clock = MutableClock(baseTime)

    @Before fun create() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        database = Room.inMemoryDatabaseBuilder(context, HistoryDatabase::class.java).build()
        repository = RoomHistoryRepository({ database.historyDao() }, clock)
    }

    @After fun cleanup() { database.close() }

    private fun snapshot(index: Int) = EnvironmentSnapshot(
        areaLabel = "测试区域 $index · 非用户位置",
        updatedAt = baseTime.minusSeconds(600).plusNanos(index.toLong()),
        metrics = listOf(
            EnvironmentMetric("temperature", "室外温度", "20.$index", "°C", DataSource.REGIONAL_API, "区域数据"),
            EnvironmentMetric("aqi", "空气质量", "—", "AQI", DataSource.REGIONAL_API, "本次无空气数据"),
            EnvironmentMetric("windDirection", "风向", "东东北", "", DataSource.REGIONAL_API, "风的来向", 68.0),
            EnvironmentMetric("altitude", "海拔", "—", "m", DataSource.ESTIMATED, "尚无海拔估计"),
        ),
        observedAt = if (index % 2 == 0) baseTime.minusSeconds(900).plusNanos(index.toLong()) else null,
        attributions = listOf("https://example.org/source?a=1&b=2", "测试数据来源 $index"),
        notices = listOf("空气数据缺失", "含逗号,双引号\"与换行\n的说明 $index"),
        isDemo = false,
    )

    private suspend fun save(index: Int): EnvironmentRecord {
        clock.time = baseTime.plusSeconds((index % 11).toLong())
        val draft = SnapshotDraft("record-${index.toString().padStart(4, '0')}", snapshot(index), index % 2 == 0)
        val note = "测试备注 $index, \"quoted\"\n下一行"
        repository.save(draft, note)
        return EnvironmentRecord(draft.id, clock.time, draft.wasCached, draft.snapshot, note)
    }

    @Test fun emptyDatabaseVisitsNothingAndReturnsZero() = runBlocking {
        var called = false
        assertEquals(0, repository.visitRecordsForExport { called = true })
        assertFalse(called)
        assertTrue(repository.observeRecords().first().isEmpty())
    }

    @Test fun multipleBatchesKeepStableNewestFirstOrderAndEveryRecordField() = runBlocking {
        val expected = (0 until 137).map { save(it) }.sortedWith(
            compareByDescending<EnvironmentRecord> { it.savedAt }.thenByDescending { it.id })
        val visited = mutableListOf<EnvironmentRecord>()
        val count = repository.visitRecordsForExport {
            assertNotEquals(android.os.Looper.getMainLooper().thread, Thread.currentThread())
            visited += it
        }
        assertEquals(137, count)
        assertEquals(expected, visited)
        assertEquals(137, visited.map { it.id }.toSet().size)
        assertEquals(expected.map { it.id }, repository.observeRecords().first().map { it.id })
    }

    @Test fun callbackFailurePropagatesWithoutAlteringRowsOrNotes() = runBlocking {
        val expected = (0 until 4).map { save(it) }
        val before = repository.observeRecords().first()
        val failure = IOException("Synthetic export destination failure")
        var delivered = 0
        try {
            repository.visitRecordsForExport {
                if (++delivered == 3) throw failure
            }
            fail("Export callback failure was swallowed")
        } catch (actual: IOException) { assertSame(failure, actual) }
        assertEquals(3, delivered)
        assertEquals(before, repository.observeRecords().first())
        for (record in expected) assertEquals(record, repository.observeRecord(record.id).first())
    }

    @Test fun coroutineCancellationStopsBetweenRowsAndLeavesDatabaseIntact() = runBlocking {
        val expected = (0 until 70).map { save(it) }
        val enteredFirstRecord = CompletableDeferred<Unit>()
        val releaseFirstRecord = CountDownLatch(1)
        var delivered = 0
        val export = launch(Dispatchers.Default) {
            repository.visitRecordsForExport {
                if (++delivered == 1) {
                    enteredFirstRecord.complete(Unit)
                    check(releaseFirstRecord.await(5, TimeUnit.SECONDS)) { "Test callback was not released" }
                }
            }
        }
        try {
            withTimeout(10000) { enteredFirstRecord.await() }
            export.cancel()
        } finally { releaseFirstRecord.countDown() }
        withTimeout(10000) { export.join() }
        assertTrue(export.isCancelled)
        assertEquals(1, delivered)
        assertEquals(70, repository.observeRecords().first().size)
        for (record in expected) assertEquals(record, repository.observeRecord(record.id).first())
    }

    @Test fun ordinaryNoteEditAndDeleteWorkAfterSuccessfulExport() = runBlocking {
        val first = save(0)
        val second = save(1)
        assertEquals(2, repository.visitRecordsForExport { })
        repository.updateNote(first.id, "修改后的备注")
        repository.delete(second.id)
        val remaining = mutableListOf<EnvironmentRecord>()
        assertEquals(1, repository.visitRecordsForExport { remaining += it })
        assertEquals(first.copy(note = "修改后的备注"), remaining.single())
        assertNull(repository.observeRecord(second.id).first())
    }
}

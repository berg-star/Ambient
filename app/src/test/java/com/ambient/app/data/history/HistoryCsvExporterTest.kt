package com.ambient.app.data.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryExportDraft
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.IOException
import java.io.OutputStream
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID

class HistoryCsvExporterTest {
    private val now = Instant.parse("2026-10-16T09:10:11Z")
    private val baseRecord = EnvironmentRecord(
        id = "first-snapshot",
        savedAt = now.minusSeconds(30),
        wasCached = true,
        snapshot = EnvironmentSnapshot(
            areaLabel = "测试区域 · 非用户位置",
            updatedAt = now.minusSeconds(900),
            metrics = listOf(EnvironmentMetric("temperature", "室外温度", "20.15", "°C", DataSource.REGIONAL_API, "测试区域数据")),
            isDemo = false,
        ),
        note = "original-note",
    )

    private class FakeRepository(initial: List<EnvironmentRecord>) : HistoryRepository {
        var records: List<EnvironmentRecord> = initial
        var visits = 0
        var afterRecord: ((Int) -> Unit)? = null
        override fun observeRecords(): Flow<List<RecordSummary>> = flowOf(emptyList())
        override fun observeRecord(id: String): Flow<EnvironmentRecord?> = flowOf(records.firstOrNull { it.id == id })
        override suspend fun visitRecordsForExport(consume: (EnvironmentRecord) -> Unit): Int {
            visits++
            val snapshot = records.toList()
            snapshot.forEachIndexed { index, record ->
                consume(record)
                afterRecord?.invoke(index + 1)
            }
            return snapshot.size
        }
        override suspend fun save(draft: SnapshotDraft, note: String): String = error("Export must not save records")
        override suspend fun updateNote(id: String, note: String) = error("Export must not edit records")
        override suspend fun delete(id: String) = error("Export must not delete records")
    }

    private class RecordingStream(
        private val failAfterBytes: Int? = null,
        private val writeFailure: IOException? = null,
        private val flushFailure: IOException? = null,
        private val closeFailure: IOException? = null,
    ) : OutputStream() {
        val bytes = ByteArrayOutputStream()
        val events = mutableListOf<String>()
        var closed = false
        override fun write(value: Int) {
            if (failAfterBytes != null && bytes.size() >= failAfterBytes) throw checkNotNull(writeFailure)
            bytes.write(value)
        }
        override fun write(buffer: ByteArray, offset: Int, length: Int) {
            for (index in offset until offset + length) write(buffer[index].toInt())
        }
        override fun flush() {
            events += "flush"
            flushFailure?.let { throw it }
        }
        override fun close() {
            closed = true
            events += "close"
            closeFailure?.let { throw it }
        }
        fun text(): String = bytes.toString(Charsets.UTF_8.name())
    }

    private data class Fixture(val directory: File, val repository: FakeRepository, val exporter: HistoryCsvExporter)

    private fun withFixture(block: suspend (Fixture) -> Unit) = runBlocking {
        val parent = File(checkNotNull(System.getProperty("java.io.tmpdir"))).canonicalFile
        val directory = File(parent, "ambient-csv-export-test-${UUID.randomUUID()}").canonicalFile
        check(directory.parentFile == parent && directory.name.startsWith("ambient-csv-export-test-"))
        check(directory.mkdir())
        val repository = FakeRepository(listOf(baseRecord))
        try {
            block(Fixture(directory, repository, HistoryCsvExporter(directory, repository, Clock.fixed(now, ZoneOffset.UTC))))
        } finally {
            // This random test directory is the only recursive deletion target.
            check(directory.canonicalFile.parentFile == parent && directory.name.startsWith("ambient-csv-export-test-"))
            check(directory.deleteRecursively())
        }
    }

    private fun Fixture.files(): List<File> = directory.listFiles()!!.toList()

    private suspend fun assertExpired(exporter: HistoryCsvExporter, draft: HistoryExportDraft) {
        var opened = false
        try {
            exporter.write(draft) { opened = true; RecordingStream() }
            fail("Expired export was accepted")
        } catch (_: HistoryException) { }
        assertFalse(opened)
    }

    @Test fun preparedCsvStaysFrozenWhenRecordsChangeBeforeChoosingDestination() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        assertEquals(1, fixture.files().size)
        fixture.repository.records = listOf(baseRecord.copy(
            note = "edited-after-prepare",
            snapshot = baseRecord.snapshot.copy(metrics = listOf(baseRecord.snapshot.metrics.single().copy(value = "99.99"))),
        ))
        val stream = RecordingStream()
        fixture.exporter.write(draft) { stream }
        assertEquals(1, fixture.repository.visits)
        assertTrue(stream.text().contains("original-note"))
        assertTrue(stream.text().contains("20.15"))
        assertFalse(stream.text().contains("edited-after-prepare"))
        assertFalse(stream.text().contains("99.99"))
        assertTrue(fixture.files().isEmpty())
    }

    @Test fun successfulWriteFlushesThenClosesAndConsumesPrivateCache() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        assertEquals("Ambient-history-20261016-091011Z.csv", draft.fileName)
        val stream = RecordingStream()
        fixture.exporter.write(draft) { stream }
        assertEquals(listOf("flush", "close"), stream.events)
        assertTrue(stream.closed)
        assertTrue(stream.text().startsWith("\uFEFF"))
        assertTrue(stream.text().contains("测试区域 · 非用户位置"))
        assertTrue(fixture.files().isEmpty())
        assertExpired(fixture.exporter, draft)
    }

    @Test fun failedDestinationOpenPropagatesAndDiscardsPreparedFile() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        val failure = IOException("Synthetic destination open failure")
        try {
            fixture.exporter.write(draft) { throw failure }
            fail("Failed destination open reported success")
        } catch (actual: IOException) { assertEquals(failure.message, actual.message) }
        assertTrue(fixture.files().isEmpty())
        assertExpired(fixture.exporter, draft)
    }

    @Test fun partialWriteFailurePropagatesClosesDestinationAndDiscardsPreparedFile() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        val failure = IOException("Synthetic partial destination failure")
        val stream = RecordingStream(failAfterBytes = 17, writeFailure = failure)
        try {
            fixture.exporter.write(draft) { stream }
            fail("Partial write reported success")
        } catch (actual: IOException) { assertEquals(failure.message, actual.message) }
        assertEquals(17, stream.bytes.size())
        assertTrue(stream.closed)
        assertEquals(listOf("close"), stream.events)
        assertTrue(fixture.files().isEmpty())
        assertExpired(fixture.exporter, draft)
    }

    @Test fun flushFailureCannotReportSuccessAndStillClosesAndCleansUp() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        val failure = IOException("Synthetic flush failure")
        val stream = RecordingStream(flushFailure = failure)
        try {
            fixture.exporter.write(draft) { stream }
            fail("Failed flush reported success")
        } catch (actual: IOException) { assertEquals(failure.message, actual.message) }
        assertEquals(listOf("flush", "close"), stream.events)
        assertTrue(stream.closed)
        assertTrue(fixture.files().isEmpty())
        assertExpired(fixture.exporter, draft)
    }

    @Test fun closeFailureCannotReportSuccessEvenAfterAllBytesWereWritten() = withFixture { fixture ->
        val draft = fixture.exporter.prepare()
        val failure = IOException("Synthetic close failure")
        val stream = RecordingStream(closeFailure = failure)
        try {
            fixture.exporter.write(draft) { stream }
            fail("Failed close reported success")
        } catch (actual: IOException) { assertEquals(failure.message, actual.message) }
        assertTrue(stream.text().contains("original-note"))
        assertEquals(listOf("flush", "close"), stream.events)
        assertTrue(fixture.files().isEmpty())
        assertExpired(fixture.exporter, draft)
    }

    @Test fun emptyHistoryRejectsPrepareAndRemovesPartialCsv() = withFixture { fixture ->
        fixture.repository.records = emptyList()
        try {
            fixture.exporter.prepare()
            fail("Empty history prepared an export")
        } catch (_: HistoryException) { }
        assertTrue(fixture.files().isEmpty())
    }

    @Test fun repositoryFailureAfterWritingOneRecordRemovesPartialCsvAndAllowsRetry() = withFixture { fixture ->
        fixture.repository.records = listOf(baseRecord, baseRecord.copy(id = "second"))
        val failure = IOException("Synthetic history read failure")
        fixture.repository.afterRecord = { if (it == 1) throw failure }
        try {
            fixture.exporter.prepare()
            fail("Partial history read prepared an export")
        } catch (actual: IOException) { assertEquals(failure.message, actual.message) }
        assertTrue(fixture.files().isEmpty())
        fixture.repository.afterRecord = null
        val draft = fixture.exporter.prepare()
        assertEquals(2, draft.recordCount)
        fixture.exporter.discard(draft)
        assertTrue(fixture.files().isEmpty())
    }

    @Test fun cancellationDuringPreparationPropagatesAndRemovesPartialCsv() = withFixture { fixture ->
        val failure = CancellationException("Synthetic export cancellation")
        fixture.repository.afterRecord = { throw failure }
        try {
            fixture.exporter.prepare()
            fail("Cancelled preparation reported success")
        } catch (actual: CancellationException) { assertEquals(failure.message, actual.message) }
        assertTrue(fixture.files().isEmpty())
        assertEquals(listOf(baseRecord), fixture.repository.records)
    }

    @Test fun discardRemovesOnlyItsOwnDraftAndOtherDraftRemainsWritable() = withFixture { fixture ->
        val cancelled = fixture.exporter.prepare()
        val retained = fixture.exporter.prepare()
        assertEquals(2, fixture.files().size)
        fixture.exporter.discard(cancelled)
        fixture.exporter.discard(cancelled)
        assertEquals(1, fixture.files().size)
        assertExpired(fixture.exporter, cancelled)
        val stream = RecordingStream()
        fixture.exporter.write(retained) { stream }
        assertTrue(stream.text().contains("first-snapshot"))
        assertTrue(fixture.files().isEmpty())
    }

    @Test fun prepareReturnsActualRecordAndMetricCountsForMultipleRecords() = withFixture { fixture ->
        fixture.repository.records = listOf(baseRecord, baseRecord.copy(
            id = "second-snapshot",
            snapshot = baseRecord.snapshot.copy(metrics = baseRecord.snapshot.metrics + EnvironmentMetric(
                "humidity", "相对湿度", "50", "%", DataSource.REGIONAL_API, "测试湿度")),
        ))
        val draft = fixture.exporter.prepare()
        assertEquals(2, draft.recordCount)
        assertEquals(3, draft.metricCount)
        val stream = RecordingStream()
        fixture.exporter.write(draft) { stream }
        assertTrue(stream.text().contains("first-snapshot"))
        assertTrue(stream.text().contains("second-snapshot"))
        assertEquals(listOf(baseRecord.id, "second-snapshot"), fixture.repository.records.map { it.id })
        assertTrue(fixture.files().isEmpty())
    }

    @Test fun abandonedCacheCleanupKeepsActiveDraftsRecentFilesAndUnrelatedFiles() = withFixture { fixture ->
        val active = fixture.exporter.prepare()
        val activeFile = fixture.files().single()
        val staleTime = now.minus(Duration.ofDays(2)).toEpochMilli()
        assertTrue(activeFile.setLastModified(staleTime))
        val stale = File(fixture.directory, "${UUID.randomUUID()}.csv.tmp").apply {
            writeText("abandoned")
            check(setLastModified(staleTime))
        }
        val recent = File(fixture.directory, "${UUID.randomUUID()}.csv.tmp").apply {
            writeText("recent")
            check(setLastModified(now.toEpochMilli()))
        }
        val unrelated = File(fixture.directory, "unrelated.txt").apply {
            writeText("preserve")
            check(setLastModified(staleTime))
        }
        val second = fixture.exporter.prepare()
        assertFalse(stale.exists())
        assertTrue(activeFile.exists())
        assertTrue(recent.exists())
        assertEquals("preserve", unrelated.readText())
        fixture.exporter.discard(second)
        val stream = RecordingStream()
        fixture.exporter.write(active) { stream }
        assertTrue(stream.text().contains("original-note"))
        assertTrue(recent.exists())
        assertTrue(unrelated.exists())
    }
}

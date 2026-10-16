package com.ambient.app.ui.history

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.history.HistoryExportDraft
import com.ambient.app.domain.history.HistoryExportService
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class HistoryExportViewModelTest {
    private val models = mutableListOf<HistoryExportViewModel>()

    @After fun cleanup() = onMain {
        models.forEach { it.viewModelScope.cancel() }
        models.clear()
    }

    @Test fun repeatedPrepareWhileBusyStartsOnlyOnePreparation() = runBlocking {
        val service = FakeExporter().apply { prepareGate = CompletableDeferred() }
        val model = createModel(service)
        onMain { model.prepare(); model.prepare() }
        assertEquals(ExportStage.PREPARING, model.state.value.stage)
        assertEquals(1, service.prepareCalls.get())
        service.prepareGate!!.complete(Unit)
        awaitState(model) { it.stage == ExportStage.CHOOSING }
        assertEquals(1, service.prepareCalls.get())
        assertNull(model.state.value.message)
    }

    @Test fun replacementUiCannotClaimTheSamePickerTwice() = runBlocking {
        val service = FakeExporter()
        val model = choosing(service)
        var first: HistoryExportDraft? = null
        var replacement: HistoryExportDraft? = null
        onMain { first = model.claimPicker(); replacement = model.claimPicker() }
        assertEquals(service.draft, first)
        assertNull(replacement)
        assertTrue(model.state.value.pickerLaunched)
        assertEquals(ExportStage.CHOOSING, model.state.value.stage)
        assertEquals(0, service.writeCalls.get())
    }

    @Test fun duplicateDestinationCallbacksWriteAndOpenOnlyOnce() = runBlocking {
        val service = FakeExporter().apply { writeGate = CompletableDeferred() }
        val model = choosing(service)
        val openCalls = AtomicInteger()
        val output = ByteArrayOutputStream()
        val open = { openCalls.incrementAndGet(); output as OutputStream }
        onMain {
            assertNotNull(model.claimPicker())
            model.destination(open)
            model.destination(open)
        }
        assertEquals(ExportStage.WRITING, model.state.value.stage)
        assertEquals(1, service.writeCalls.get())
        assertEquals(0, openCalls.get())
        service.writeGate!!.complete(Unit)
        val finished = awaitState(model) { it.stage == ExportStage.IDLE && it.message != null }
        assertEquals(1, openCalls.get())
        assertEquals(1, service.writeCalls.get())
        assertEquals("csv", output.toString("UTF-8"))
        assertTrue(finished.message!!.startsWith("已导出"))
        assertNull(finished.error)
    }

    @Test fun preparationFailureNeverOpensOutputOrReportsSuccess() = runBlocking {
        val service = FakeExporter().apply { prepareFailure = IOException("Synthetic preparation failure") }
        val model = createModel(service)
        val openCalls = AtomicInteger()
        onMain { model.prepare() }
        val failed = awaitState(model) { it.stage == ExportStage.IDLE && it.error != null }
        assertTrue(failed.error!!.contains("导出准备失败"))
        assertNull(failed.message)
        assertNull(failed.draft)
        // A stale result must not turn a failed preparation into a file write.
        onMain { model.destination { openCalls.incrementAndGet(); ByteArrayOutputStream() } }
        assertEquals(0, openCalls.get())
        assertEquals(0, service.writeCalls.get())
        assertNull(model.state.value.message)
    }

    @Test fun cancelDiscardsDraftWithoutWriting() = runBlocking {
        val service = FakeExporter()
        val model = choosing(service)
        onMain { model.claimPicker(); model.destination(null) }
        val finished = awaitState(model) { it.stage == ExportStage.IDLE && it.message != null }
        assertEquals(1, service.discardCalls.get())
        assertEquals(0, service.writeCalls.get())
        assertTrue(finished.message!!.contains("已取消导出"))
        assertNull(finished.error)
        assertNull(finished.draft)
    }

    @Test fun outputFailureReportsFailureWithoutFalseSuccess() = runBlocking {
        val service = FakeExporter()
        val model = choosing(service)
        val openCalls = AtomicInteger()
        onMain {
            model.claimPicker()
            model.destination {
                openCalls.incrementAndGet()
                throw IOException("Synthetic destination failure")
            }
        }
        val failed = awaitState(model) { it.stage == ExportStage.IDLE && it.error != null }
        assertEquals(1, service.writeCalls.get())
        assertEquals(1, openCalls.get())
        assertTrue(failed.error!!.contains("导出未完成"))
        assertNull(failed.message)
        assertFalse(failed.busy)
    }

    @Test fun pickerFailureWithFailedCleanupReturnsIdleWithoutCrashing() = runBlocking {
        val service = FakeExporter().apply { discardFailure = IOException("Synthetic cleanup failure") }
        val model = choosing(service)
        onMain { model.claimPicker(); model.pickerFailed() }
        val failed = awaitState(model) { it.stage == ExportStage.IDLE && it.error != null }
        assertEquals(1, service.discardCalls.get())
        assertEquals(0, service.writeCalls.get())
        assertTrue(failed.error!!.contains("系统保存入口未能打开"))
        assertNull(failed.message)
        assertNull(failed.draft)
        assertFalse(failed.busy)
    }

    @Test fun freshModelRejectsResultFromPreviousProcessWithoutOpeningOutput() {
        val service = FakeExporter()
        val model = createModel(service)
        val openCalls = AtomicInteger()
        onMain { model.destination { openCalls.incrementAndGet(); ByteArrayOutputStream() } }
        assertEquals(0, openCalls.get())
        assertEquals(0, service.prepareCalls.get())
        assertEquals(0, service.writeCalls.get())
        assertEquals(ExportStage.IDLE, model.state.value.stage)
        assertTrue(model.state.value.error!!.contains("导出已中断"))
        assertNull(model.state.value.message)
    }

    private fun onMain(action: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync { action() }

    private fun createModel(service: HistoryExportService): HistoryExportViewModel {
        lateinit var model: HistoryExportViewModel
        onMain { model = HistoryExportViewModel(service); models += model }
        return model
    }

    private suspend fun choosing(service: FakeExporter): HistoryExportViewModel {
        val model = createModel(service)
        onMain { model.prepare() }
        awaitState(model) { it.stage == ExportStage.CHOOSING }
        return model
    }

    private suspend fun awaitState(model: HistoryExportViewModel, predicate: (HistoryExportState) -> Boolean) =
        withTimeout(10000) { model.state.first(predicate) }

    private class FakeExporter : HistoryExportService {
        val draft = HistoryExportDraft("draft-1", "Ambient-history-test.csv", recordCount = 2, metricCount = 6)
        val prepareCalls = AtomicInteger()
        val writeCalls = AtomicInteger()
        val discardCalls = AtomicInteger()
        var prepareGate: CompletableDeferred<Unit>? = null
        var writeGate: CompletableDeferred<Unit>? = null
        var prepareFailure: Exception? = null
        var discardFailure: Exception? = null

        override suspend fun prepare(): HistoryExportDraft {
            prepareCalls.incrementAndGet()
            prepareGate?.await()
            prepareFailure?.let { throw it }
            return draft
        }

        override suspend fun write(draft: HistoryExportDraft, openOutput: () -> OutputStream) {
            assertEquals(this.draft, draft)
            writeCalls.incrementAndGet()
            writeGate?.await()
            openOutput().use { it.write("csv".toByteArray(Charsets.UTF_8)) }
        }

        override suspend fun discard(draft: HistoryExportDraft) {
            assertEquals(this.draft, draft)
            discardCalls.incrementAndGet()
            discardFailure?.let { throw it }
        }
    }
}

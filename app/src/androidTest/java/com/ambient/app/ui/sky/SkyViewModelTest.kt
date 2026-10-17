package com.ambient.app.ui.sky

import androidx.lifecycle.viewModelScope
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.sky.SkyRepository
import com.ambient.app.domain.sky.SkyResult
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.atomic.AtomicInteger

@RunWith(AndroidJUnit4::class)
class SkyViewModelTest {
    private fun onMain(block: () -> Unit) = InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    @Test fun constructionDoesNotRequestAndDuplicateLoadsAreIgnored(): Unit = runBlocking {
        val calls = AtomicInteger()
        val gate = CompletableDeferred<Unit>()
        val model = SkyViewModel(SkyRepository { calls.incrementAndGet(); gate.await(); SkyResult(null) })
        try {
            assertEquals(0, calls.get())
            onMain { model.load(); model.load(true) }
            assertEquals(1, calls.get()); assertTrue(model.state.value.loading)
            gate.complete(Unit)
            withTimeout(5000) { model.state.first { !it.loading } }
        } finally { model.viewModelScope.cancel() }
    }
    @Test fun leavingCancelsAndResumeCanLoadAgain(): Unit = runBlocking {
        val calls = AtomicInteger(); val cancelled = CompletableDeferred<Unit>()
        val model = SkyViewModel(SkyRepository {
            if (calls.incrementAndGet() == 1) try { awaitCancellation() } finally { cancelled.complete(Unit) }
            SkyResult(null, listOf("重新进入"))
        })
        try {
            onMain { model.load(); model.stop() }
            withTimeout(5000) { cancelled.await() }
            onMain { model.load() }
            withTimeout(5000) { model.state.first { it.result.messages.contains("重新进入") } }
            assertEquals(2, calls.get())
        } finally { model.viewModelScope.cancel() }
    }
    @Test fun oldNonCooperativeResultCannotOverwriteNewAreaAfterReset(): Unit = runBlocking {
        val oldGate = CompletableDeferred<Unit>(); val oldReturned = CompletableDeferred<Unit>(); val calls = AtomicInteger()
        val model = SkyViewModel(SkyRepository {
            if (calls.incrementAndGet() == 1) withContext(NonCancellable) {
                oldGate.await(); oldReturned.complete(Unit); SkyResult(null, listOf("旧区域"))
            }
            else SkyResult(null, listOf("新区域"))
        })
        try {
            onMain { model.load(); model.reset(); model.load() }
            withTimeout(5000) { model.state.first { it.result.messages.contains("新区域") } }
            oldGate.complete(Unit)
            withTimeout(5000) { oldReturned.await() }
            onMain { }
            assertEquals(listOf("新区域"), model.state.value.result.messages)
        } finally { oldGate.complete(Unit); model.viewModelScope.cancel() }
    }
    @Test fun failureIsRecoverableWithoutExposingExceptionDetails(): Unit = runBlocking {
        var fail = true
        val model = SkyViewModel(SkyRepository { if (fail) error("sensitive-internal-fixture") else SkyResult(null) })
        try {
            onMain { model.load() }
            assertFalse(model.state.value.loading)
            assertFalse(model.state.value.result.messages.toString().contains("sensitive"))
            fail = false; onMain { model.load(true) }
            assertTrue(model.state.value.result.messages.isEmpty())
        } finally { model.viewModelScope.cancel() }
    }
}

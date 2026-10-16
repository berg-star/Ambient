package com.ambient.app.data.environment

import com.ambient.app.domain.environment.EnvironmentDataService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class CachedEnvironmentRepositoryTest {
    private val loadedAt = Instant.parse("2026-10-15T12:00:00Z")
    private val clock = Clock.fixed(loadedAt, ZoneOffset.UTC)

    @Test
    fun failureRetainsPreviousSnapshotAndItsOriginalLoadTime() = runBlocking {
        val original = DemoEnvironmentDataService(clock).fetch()
        var failNextFetch = false
        val repository = CachedEnvironmentRepository(
            service = EnvironmentDataService {
                if (failNextFetch) throw IOException("Test network unavailable")
                original
            },
            dispatcher = Dispatchers.Unconfined,
        )

        val success = repository.refresh()
        assertSame(original, success.snapshot)
        assertNull(success.error)

        failNextFetch = true
        val failed = repository.refresh()
        assertSame(original, failed.snapshot)
        assertEquals(loadedAt, failed.snapshot?.updatedAt)
        assertNull(failed.snapshot?.observedAt)
        assertNotNull(failed.error)
    }

    @Test
    fun failureWithoutACacheDoesNotInventASnapshot() = runBlocking {
        val repository = CachedEnvironmentRepository(
            service = EnvironmentDataService { throw IOException("Test network unavailable") },
            dispatcher = Dispatchers.Unconfined,
        )
        val result = repository.refresh()
        assertNull(result.snapshot)
        assertNotNull(result.error)
    }

    @Test
    fun cancellationPropagatesInsteadOfBecomingAnErrorAndKeepsExistingCache() = runBlocking {
        val original = DemoEnvironmentDataService(clock).fetch()
        var requestCount = 0
        val repository = CachedEnvironmentRepository(
            service = EnvironmentDataService {
                when (++requestCount) {
                    1 -> original
                    2 -> throw CancellationException("Test request cancelled")
                    else -> throw IOException("Test network unavailable")
                }
            },
            dispatcher = Dispatchers.Unconfined,
        )
        repository.refresh()

        try {
            repository.refresh()
            fail("Cancellation must propagate to the caller")
        } catch (cancelled: CancellationException) {
            assertEquals("Test request cancelled", cancelled.message)
        }

        val afterCancellation = repository.refresh()
        assertSame(original, afterCancellation.snapshot)
        assertEquals(loadedAt, afterCancellation.snapshot?.updatedAt)
        assertNotNull(afterCancellation.error)
    }
}

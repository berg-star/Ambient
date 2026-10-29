package com.ambient.app.data.precipitation

import com.ambient.app.domain.environment.*
import com.ambient.app.domain.precipitation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CachedPrecipitationLoaderTest {
    private class TestClock(var at: Instant = Instant.parse("2026-10-17T04:00:00Z")) : Clock() {
        override fun instant() = at
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
    }
    private val clock = TestClock()
    private val area = EnvironmentArea("test",30.0,120.0)
    private val calls = mutableListOf<String>()
    private var failure: String? = null
    private var cancel = false
    private val service = object : PrecipitationDataService {
        fun get(part: String): PrecipitationForecast {
            calls += part
            if (cancel) throw CancellationException()
            if (failure == part || failure == "all") throw EnvironmentException("离线测试")
            return PrecipitationForecast(listOf(PrecipitationPoint(clock.millis(), 0.0)), clock.millis())
        }
        override suspend fun minutely(area: EnvironmentArea) = get("minutely")
        override suspend fun hourly(area: EnvironmentArea) = get("hourly")
    }
    private val loader = CachedPrecipitationLoader(service, clock)
    @Test fun firstOpenRequestsTwoAndReopenReusesCache() = runBlocking {
        val first = loader.load(area,null,false)
        val next = loader.load(area,first.snapshot,false)
        assertEquals(listOf("minutely","hourly"), calls); assertTrue(next.isCached)
        assertFalse(first.minutelyCached); assertFalse(first.hourlyCached)
        assertTrue(next.minutelyCached); assertTrue(next.hourlyCached)
    }
    @Test fun fiveMinutesLaterOnlyMinutelyRefreshes() = runBlocking {
        val first = loader.load(area,null,false)
        clock.at = clock.at.plusSeconds(300)
        val result = loader.load(area,first.snapshot,false)
        assertEquals(listOf("minutely","hourly","minutely"),calls)
        assertFalse(result.isCached); assertFalse(result.minutelyCached); assertTrue(result.hourlyCached)
        assertEquals(first.snapshot!!.hourly, result.snapshot!!.hourly)
    }
    @Test fun forceIsThrottledAndAllowedAfterThirtySeconds() = runBlocking {
        val first = loader.load(area,null,false)
        val throttled = loader.load(area,first.snapshot,true)
        assertTrue(throttled.messages.single().contains("30 秒"))
        assertTrue(throttled.minutelyCached); assertTrue(throttled.hourlyCached)
        clock.at = clock.at.plusSeconds(30)
        loader.load(area,first.snapshot,true); assertEquals(4,calls.size)
    }
    @Test fun oneApiFailureStillShowsOtherAndDoesNotRetry() = runBlocking {
        failure = "minutely"
        val result = loader.load(area,null,false)
        assertNull(result.snapshot!!.minutely); assertNotNull(result.snapshot.hourly)
        assertEquals(1,result.messages.size); assertEquals(2,calls.size)
        assertFalse(result.minutelyCached); assertFalse(result.hourlyCached)
    }
    @Test fun offlineKeepsOriginalTimesWithoutExtendingCoverage() = runBlocking {
        val first = loader.load(area,null,false)
        clock.at = clock.at.plusSeconds(3600); failure = "all"
        val result = loader.load(area,first.snapshot,true)
        assertEquals(first.snapshot,result.snapshot); assertTrue(result.isCached)
        assertTrue(result.minutelyCached); assertTrue(result.hourlyCached)
        assertTrue(forecastPoints(result.snapshot!!.minutely,ForecastRange.TWO_HOURS,clock.at).isEmpty())
    }
    @Test fun sameLabelDifferentCoordinateCannotReuseOldCity() = runBlocking {
        val first = loader.load(area,null,false); failure = "all"
        assertNull(loader.load(area.copy(latitude=31.0),first.snapshot,false).snapshot)
    }
    @Test fun cancellationStopsRemainingRequests() = runBlocking {
        cancel=true
        try { loader.load(area,null,false); fail() } catch (_: CancellationException) { }
        assertEquals(listOf("minutely"),calls)
    }
    @Test fun partialRefreshMarksOnlyTheRetainedForecastAsCached() = runBlocking {
        for (failedPart in listOf("minutely", "hourly")) {
            failure = null
            val independentLoader = CachedPrecipitationLoader(service, clock)
            val old = independentLoader.load(area, null, false).snapshot!!
            clock.at = clock.at.plusSeconds(30)
            failure = failedPart
            val result = independentLoader.load(area, old, true)
            assertFalse(result.isCached)
            assertEquals(failedPart == "minutely", result.minutelyCached)
            assertEquals(failedPart == "hourly", result.hourlyCached)
            if (failedPart == "minutely") {
                assertEquals(old.minutely, result.snapshot!!.minutely)
                assertEquals(clock.millis(), result.snapshot.hourly!!.fetchedAt)
            } else {
                assertEquals(old.hourly, result.snapshot!!.hourly)
                assertEquals(clock.millis(), result.snapshot.minutely!!.fetchedAt)
            }
        }
    }
    @Test fun legacyResultDefaultsOnlyMarkPresentPartsAsCached() {
        val forecast = PrecipitationForecast(emptyList(), clock.millis())
        val result = PrecipitationResult(PrecipitationSnapshot(area, hourly = forecast), isCached = true)
        assertFalse(result.minutelyCached); assertTrue(result.hourlyCached)
        assertFalse(PrecipitationResult(null, isCached = true).hourlyCached)
    }
}

package com.ambient.app.data.sky

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.sky.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CachedSkyLoaderTest {
    private class TestClock(var now: Instant = Instant.parse("2026-10-17T04:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private class Service(val clock: Clock) : AstronomyDataService {
        val calls = mutableListOf<String>()
        var failure: String? = null
        var cancel = false
        private fun call(part: String) {
            calls += part
            if (cancel) throw CancellationException()
            if (failure == "all" || failure == part) throw EnvironmentException("离线测试")
        }
        override suspend fun sun(area: EnvironmentArea, date: String): SunTimes {
            call("sun"); return SunTimes(null, null, clock.millis())
        }
        override suspend fun moon(area: EnvironmentArea, date: String): MoonDay {
            call("moon"); return MoonDay(emptyList(), clock.millis())
        }
        override suspend fun solar(area: EnvironmentArea, at: Instant, zone: ZoneId): SolarPosition {
            call("solar"); return SolarPosition(10.0, 180.0, at.toEpochMilli(), clock.millis())
        }
    }
    private val area = EnvironmentArea("测试区域", 30.0, 120.0, timeZone = "Asia/Shanghai")
    private val clock = TestClock()
    private val service = Service(clock)
    private val loader = CachedSkyLoader(service, clock) { ZoneId.of("UTC") }

    @Test fun firstLoadRequestsThreePartsAndReopenReusesFreshCache() = runBlocking {
        val first = loader.load(area, null, false)
        val second = loader.load(area, first.snapshot, false)
        assertEquals(listOf("sun", "moon", "solar"), service.calls)
        assertTrue(second.isCached); assertEquals(first.snapshot, second.snapshot)
        assertFalse(first.sunCached); assertFalse(first.moonCached); assertFalse(first.solarCached)
        assertTrue(second.sunCached); assertTrue(second.moonCached); assertTrue(second.solarCached)
    }
    @Test fun afterTenMinutesOnlySolarAngleIsUpdated() = runBlocking {
        val old = loader.load(area, null, false).snapshot
        clock.now = clock.now.plusSeconds(600)
        val fresh = loader.load(area, old, false)
        assertEquals(listOf("sun", "moon", "solar", "solar"), service.calls)
        assertEquals(old!!.sun, fresh.snapshot!!.sun)
        assertNotEquals(old.solar!!.calculatedFor, fresh.snapshot.solar!!.calculatedFor)
        assertFalse(fresh.isCached)
        assertTrue(fresh.sunCached); assertTrue(fresh.moonCached); assertFalse(fresh.solarCached)
    }
    @Test fun manualRefreshIsThrottled() = runBlocking {
        val first = loader.load(area, null, false)
        val result = loader.load(area, first.snapshot, true)
        assertEquals(3, service.calls.size); assertTrue(result.messages.single().contains("30 秒"))
        assertTrue(result.sunCached); assertTrue(result.moonCached); assertTrue(result.solarCached)
        clock.now = clock.now.plusSeconds(30)
        loader.load(area, first.snapshot, true)
        assertEquals(6, service.calls.size)
    }
    @Test fun offlinePreservesOriginalTimesAndIndividualValues() = runBlocking {
        val old = loader.load(area, null, false).snapshot
        clock.now = clock.now.plusSeconds(601); service.failure = "all"
        val result = loader.load(area, old, true)
        assertEquals(old, result.snapshot); assertTrue(result.isCached); assertEquals(3, result.messages.size)
        assertTrue(result.sunCached); assertTrue(result.moonCached); assertTrue(result.solarCached)
    }
    @Test fun partialSuccessDoesNotHideOtherParts() = runBlocking {
        service.failure = "moon"
        val result = loader.load(area, null, false)
        assertNotNull(result.snapshot!!.sun); assertNotNull(result.snapshot.solar); assertNull(result.snapshot.moon)
        assertEquals(1, result.messages.size)
        assertFalse(result.sunCached); assertFalse(result.moonCached); assertFalse(result.solarCached)
    }
    @Test fun sameNameDifferentCoordinatesCannotReuseCacheOnFailure() = runBlocking {
        val old = loader.load(area, null, false).snapshot
        service.failure = "all"
        val result = loader.load(area.copy(latitude = 35.0), old, false)
        assertNull(result.snapshot); assertFalse(result.isCached)
    }
    @Test fun midnightDropsAllPreviousDayParts() = runBlocking {
        val old = loader.load(area, null, false).snapshot
        clock.now = Instant.parse("2026-10-17T16:00:00Z"); service.failure = "all"
        val result = loader.load(area, old, false)
        assertNull(result.snapshot)
    }
    @Test fun locationTimeZoneOverridesDeviceDate() = runBlocking {
        clock.now = Instant.parse("2026-10-17T01:00:00Z")
        val result = loader.load(area.copy(timeZone = "America/Los_Angeles"), null, false)
        assertEquals("2026-10-16", result.snapshot!!.date)
        assertFalse(result.snapshot.deviceZoneFallback)
    }
    @Test fun unknownOrInvalidTimeZoneIsExplicitDeviceFallback() = runBlocking {
        for (tz in listOf(null, "invalid")) {
            val result = loader.load(area.copy(timeZone = tz), null, false)
            assertTrue(result.snapshot!!.deviceZoneFallback)
            assertEquals("UTC", result.snapshot.zoneId)
        }
    }
    @Test fun cancellationPropagatesAndStopsRemainingRequests() = runBlocking {
        service.cancel = true
        try { loader.load(area, null, false); fail() } catch (_: CancellationException) { }
        assertEquals(listOf("sun"), service.calls)
    }
    @Test fun partialRefreshMarksOnlyTheRetainedCalculationAsCached() = runBlocking {
        for (failedPart in listOf("sun", "moon", "solar")) {
            service.failure = null
            val independentLoader = CachedSkyLoader(service, clock)
            val old = independentLoader.load(area, null, false).snapshot!!
            clock.now = clock.now.plusSeconds(30)
            service.failure = failedPart
            val result = independentLoader.load(area, old, true)
            assertFalse(result.isCached)
            assertEquals(failedPart == "sun", result.sunCached)
            assertEquals(failedPart == "moon", result.moonCached)
            assertEquals(failedPart == "solar", result.solarCached)
            val actual = result.snapshot!!
            assertEquals(if (failedPart == "sun") old.sun!!.fetchedAt else clock.millis(), actual.sun!!.fetchedAt)
            assertEquals(if (failedPart == "moon") old.moon!!.fetchedAt else clock.millis(), actual.moon!!.fetchedAt)
            assertEquals(if (failedPart == "solar") old.solar!!.fetchedAt else clock.millis(), actual.solar!!.fetchedAt)
        }
    }
    @Test fun legacyResultDefaultsOnlyMarkPresentPartsAsCached() {
        val result = SkyResult(SkySnapshot(area, "2026-10-17", "Asia/Shanghai", false,
            sun = SunTimes(null, null, clock.millis())), isCached = true)
        assertTrue(result.sunCached); assertFalse(result.moonCached); assertFalse(result.solarCached)
        assertFalse(SkyResult(null, isCached = true).sunCached)
    }
}

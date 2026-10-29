package com.ambient.app.domain.environment

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class DataFreshnessTest {
    private val now = Instant.parse("2026-10-29T08:00:00Z")
    private fun metric(id: String = "temperature", secondsAgo: Long = 0) = EnvironmentMetric(
        id, "测试", "20", "°C", DataSource.REGIONAL_API, "离线合成测试",
        fetchedAtMillis = now.minusSeconds(secondsAgo).toEpochMilli())
    private fun timing(metric: EnvironmentMetric) = metricTiming(metric, null, now)

    @Test fun weatherAndAirHaveIndependentReminderThresholds() {
        assertEquals(Freshness.AGING, timing(metric(secondsAgo = 599)).freshness)
        assertEquals(Freshness.OLD, timing(metric(secondsAgo = 600)).freshness)
        assertEquals(Freshness.AGING, timing(metric("aqi", 3599)).freshness)
        assertEquals(Freshness.OLD, timing(metric("pm25", 3600)).freshness)
        assertEquals(Freshness.RECENT, timing(metric(secondsAgo = 59)).freshness)
        assertEquals(Freshness.AGING, timing(metric(secondsAgo = 60)).freshness)
    }

    @Test fun recentCacheIsNotStaleAndOlderCacheStillSaysCache() {
        assertEquals("本地缓存", freshnessLabel(timing(metric()), true))
        assertEquals("刚获取", freshnessLabel(timing(metric()), false))
        assertEquals("本地缓存 · 获取较早 · 建议刷新", freshnessLabel(timing(metric(secondsAgo = 600)), true))
    }

    @Test fun newFetchDoesNotMakeOldObservationNew() {
        val oldObservation = metric().copy(observedAtMillis = now.minusSeconds(3600).toEpochMilli())
        assertEquals(Freshness.OLD, timing(oldObservation).freshness)
        assertEquals("较早观测 · 建议刷新", freshnessLabel(timing(oldObservation), false))
    }

    @Test fun futureTimesAreNotReportedAsFresh() {
        assertEquals(Freshness.CLOCK_ERROR, timing(metric(secondsAgo = -1)).freshness)
        assertEquals(Freshness.CLOCK_ERROR, timing(metric().copy(observedAtMillis = now.plusSeconds(1).toEpochMilli())).freshness)
    }

    @Test fun missingAndDemoNeverClaimAReadingTime() {
        assertEquals(MetricTiming(null, null, Freshness.MISSING), timing(metric().copy(value = "—")))
        assertEquals(MetricTiming(null, null, Freshness.DEMO), timing(metric().copy(source = DataSource.DEMO)))
    }

    @Test fun legacyCacheBatchTimeIsExplicitAndDoesNotInventObservation() {
        val legacy = metric().copy(fetchedAtMillis = null)
        val snapshot = EnvironmentSnapshot("测试区域", now.minusSeconds(120), listOf(legacy))
        val result = metricTiming(legacy, snapshot, now)
        assertTrue(result.batchTimeOnly)
        assertNull(result.observedAt)
        assertEquals(Freshness.UNKNOWN, result.freshness)
        assertEquals(snapshot.updatedAt, result.fetchedAt)
        assertEquals(Freshness.OLD, metricTiming(legacy, snapshot.copy(updatedAt = now.minusSeconds(700)), now).freshness)
    }

    @Test fun locationDoesNotBorrowWeatherTimeOrManualCityHeight() {
        val area = EnvironmentArea("上次定位", 0.0, 0.0, fromDevice = true, positioningHeightMeters = 100.0,
            positioningHeightAtMillis = now.minusSeconds(7200).toEpochMilli(), locatedAtMillis = now.minusSeconds(7200).toEpochMilli())
        val height = positioningHeightMetric(area)
        val snapshot = EnvironmentSnapshot(area.label, now, listOf(metric()))
        val result = metricTiming(height, snapshot, now)
        assertNull(result.fetchedAt)
        assertEquals(Freshness.OLD, result.freshness)
        assertEquals(locationFixTime(area), result.observedAt)
        assertEquals("—", positioningHeightMetric(area.copy(fromDevice = false)).value)
        assertNull(locationFixTime(area.copy(fromDevice = false)))
    }

    @Test fun locationTimeSurvivesWithoutHeightAndLegacyTimeStaysUnknown() {
        val area = EnvironmentArea("测试", 0.0, 0.0, fromDevice = true, locatedAtMillis = now.toEpochMilli())
        assertEquals(now, locationFixTime(area))
        assertEquals("—", positioningHeightMetric(area).value)
        assertNull(locationFixTime(area.copy(locatedAtMillis = null)))
        assertEquals(now, locationFixTime(area.copy(locatedAtMillis = null, positioningHeightAtMillis = now.toEpochMilli())))
    }
}

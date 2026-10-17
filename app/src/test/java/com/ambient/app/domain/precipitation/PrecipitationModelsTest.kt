package com.ambient.app.domain.precipitation

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PrecipitationModelsTest {
    private val now = Instant.parse("2026-10-17T04:00:00Z")
    private val at = now.toEpochMilli()
    private fun forecast(vararg offsets: Long) = PrecipitationForecast(offsets.map { PrecipitationPoint(at + it, 0.0) }, at)
    @Test fun twoHoursDoesNotIncludePastIntervalsOrBeyondHorizon() {
        val f = forecast(-300_000, 0, 300_000, 7_200_000)
        assertEquals(listOf(at, at + 300_000), forecastPoints(f, ForecastRange.TWO_HOURS, now).map { it.time })
    }
    @Test fun currentHourlyIntervalRemainsVisibleUntilItsEnd() {
        val f = forecast(-3_600_000, -1_800_000, 0, 3_600_000)
        assertEquals(3, forecastPoints(f, ForecastRange.DAY, now).size)
    }
    @Test fun dayAndThreeDaysUseDifferentWindowsWithoutInterpolation() {
        val f = forecast(*LongArray(72) { it * 3_600_000L })
        assertEquals(24, forecastPoints(f, ForecastRange.DAY, now).size)
        assertEquals(72, forecastPoints(f, ForecastRange.THREE_DAYS, now).size)
    }
    @Test fun missingValuesAndTimeGapsArePreserved() {
        val f = PrecipitationForecast(listOf(PrecipitationPoint(at, null), PrecipitationPoint(at+900_000, 0.0)), at)
        val points = forecastPoints(f, ForecastRange.TWO_HOURS, now)
        assertEquals(2, points.size); assertNull(points.first().amountMm); assertEquals(0.0, points.last().amountMm!!, 0.0)
    }
    @Test fun expiredForecastIsNotExtendedIntoFuture() {
        assertTrue(forecastPoints(forecast(-7_200_000), ForecastRange.TWO_HOURS, now).isEmpty())
        assertTrue(forecastPoints(null, ForecastRange.DAY, now).isEmpty())
    }
    @Test fun oldProviderTimeStillStaleWhenJustFetched() {
        assertTrue(forecastStale(forecast(0).copy(updatedAt = at - 900_000), ForecastRange.TWO_HOURS, now))
        assertFalse(forecastStale(forecast(0), ForecastRange.TWO_HOURS, now))
    }
    @Test fun futureClockAndCacheAgesAreExplicitlyStale() {
        assertTrue(forecastStale(forecast(0).copy(fetchedAt = at + 1), ForecastRange.DAY, now))
        assertTrue(forecastStale(forecast(0).copy(fetchedAt = at - 300_000), ForecastRange.TWO_HOURS, now))
        assertFalse(forecastStale(forecast(0).copy(fetchedAt = at - 300_000), ForecastRange.DAY, now))
    }
    @Test fun snowAndUnknownAreNotRain() {
        assertEquals("雪", precipitationType("snow")); assertEquals("类型未知", precipitationType("invalid"))
    }
}

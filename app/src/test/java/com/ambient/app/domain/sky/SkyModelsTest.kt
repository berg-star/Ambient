package com.ambient.app.domain.sky

import com.ambient.app.domain.environment.EnvironmentArea
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant

class SkyModelsTest {
    private val snapshot = SkySnapshot(EnvironmentArea("测试地点", 30.0, 120.0), "2026-10-17", "Asia/Shanghai", false,
        SunTimes("2026-10-17T06:00+08:00", "2026-10-17T18:00+08:00", 0),
        MoonDay(listOf(MoonPhase("2026-10-17T12:00+08:00", "测试月相", 30.0)), 0))
    private fun at(time: String) = Instant.parse("2026-10-17T${time}:00Z")

    @Test fun beforeSunriseCountsDownWithoutInventingSolarElevation() {
        val result = daylight(snapshot, Instant.parse("2026-10-16T21:00:00Z"))
        assertEquals("距离日出", result.headline)
        assertEquals(Duration.ofHours(1), result.remaining)
        assertEquals(0f, result.progress)
    }
    @Test fun daytimeUsesElapsedTimeForProgress() {
        val result = daylight(snapshot, at("04:00"))
        assertEquals(Duration.ofHours(12), result.duration)
        assertEquals(0.5f, result.progress)
        assertEquals(Duration.ofHours(6), result.remaining)
    }
    @Test fun exactSunriseStartsAtZero() { assertEquals(0f, daylight(snapshot, Instant.parse("2026-10-16T22:00:00Z")).progress) }
    @Test fun sunsetEndsCountdownInsteadOfUsingTodaysSunriseAsTomorrow() {
        val result = daylight(snapshot, at("10:00"))
        assertEquals(1f, result.progress)
        assertNull(result.remaining)
        assertEquals("今日日照时段已结束", result.headline)
    }
    @Test fun dateRolloverDoesNotShowYesterdaysCountdown() {
        val result = daylight(snapshot, at("16:00"))
        assertNull(result.duration); assertNull(result.progress)
        assertEquals("日期已变化，请刷新", result.headline)
    }
    @Test fun missingAndReversedSunTimesDoNotImplyPolarDay() {
        for (sun in listOf(SunTimes(null, null, 0), SunTimes(null, snapshot.sun!!.sunset, 0),
            SunTimes(snapshot.sun!!.sunset, snapshot.sun.sunrise, 0), SunTimes("bad", "bad", 0))) {
            assertNull(daylight(snapshot.copy(sun = sun), at("04:00")).progress)
        }
    }
    @Test fun moonOnlySelectsTheCurrentHour() {
        assertEquals("测试月相", currentMoon(snapshot, at("04:30"))?.name)
        assertNull(currentMoon(snapshot, at("03:59")))
        assertNull(currentMoon(snapshot, at("05:00")))
        assertNull(currentMoon(snapshot, at("16:00")))
    }
    @Test fun positioningHeightMustComeFromThisDeviceLocation() {
        assertNull(positioningHeight(snapshot.area.copy(positioningHeightMeters = 300.0)))
        assertEquals(300.0, positioningHeight(snapshot.area.copy(fromDevice = true, positioningHeightMeters = 300.0))!!, 0.0)
    }
    @Test fun invalidHeightNeverBecomesARealZero() {
        for (height in listOf(Double.NaN, Double.POSITIVE_INFINITY, 10001.0, -501.0))
            assertNull(positioningHeight(snapshot.area.copy(fromDevice = true, positioningHeightMeters = height)))
        assertNull(positioningHeight(snapshot.area.copy(fromDevice = true)))
        assertEquals(0.0, positioningHeight(snapshot.area.copy(fromDevice = true, positioningHeightMeters = 0.0))!!, 0.0)
    }
}

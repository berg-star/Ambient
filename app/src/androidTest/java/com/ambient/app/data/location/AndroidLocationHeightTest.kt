package com.ambient.app.data.location

import android.location.Location
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidLocationHeightTest {
    private fun fix() = Location("gps").apply { latitude = 30.0; longitude = 120.0 }
    @Test fun absentHeightAndPrecisionDoNotBecomeZeroMeasurements() {
        val location = fix()
        assertEquals(0.0, location.altitude, 0.0)
        assertFalse(location.hasAltitude())
        val area = areaFromLocation(location)
        assertNull(area.positioningHeightMeters); assertNull(area.verticalAccuracyMeters)
    }
    @Test fun explicitlyReportedZeroIsValid() {
        val area = areaFromLocation(fix().apply { altitude = 0.0 })
        assertEquals(0.0, area.positioningHeightMeters!!, 0.0)
    }
    @Test fun platformHeightAndVerticalAccuracyRetainTheirSourceAndUnits() {
        val area = areaFromLocation(fix().apply { altitude = 123.4; verticalAccuracyMeters = 15f })
        assertEquals(123.4, area.positioningHeightMeters!!, 0.0)
        assertEquals(15.0, area.verticalAccuracyMeters!!, 0.0)
        assertTrue(area.fromDevice); assertTrue(area.label.contains("卫星定位")); assertNull(area.timeZone)
    }
    @Test fun invalidHeightAndAccuracyAreNotClampedIntoRealReadings() {
        for (height in listOf(Double.NaN, Double.POSITIVE_INFINITY, 10001.0)) {
            val area = areaFromLocation(fix().apply { altitude = height; verticalAccuracyMeters = Float.NaN })
            assertNull(area.positioningHeightMeters); assertNull(area.verticalAccuracyMeters)
        }
    }

    @Test fun areaFixTimeSurvivesEvenWhenTheLocationHasNoAltitude() {
        val at = 1_790_000_000_000L
        val area = areaFromLocation(fix().apply { time = at })
        assertEquals(at, area.locatedAtMillis)
        assertNull(area.positioningHeightMeters)
        assertNull(area.positioningHeightAtMillis)
    }

    @Test fun absentFixTimeRemainsUnknown() {
        val area = areaFromLocation(fix().apply { altitude = 123.4 })
        assertNull(area.locatedAtMillis)
        assertNull(area.positioningHeightAtMillis)
    }
}

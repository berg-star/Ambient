package com.ambient.app.data.environment

import com.ambient.app.domain.environment.DataSource
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class DemoEnvironmentDataServiceTest {
    @Test
    fun fixturesAreExplicitlyDemoWithoutAnObservationTime() = runBlocking {
        val fixedInstant = Instant.parse("2026-10-15T12:00:00Z")
        val service = DemoEnvironmentDataService(Clock.fixed(fixedInstant, ZoneOffset.UTC))
        val snapshot = service.fetch()

        assertEquals(fixedInstant, snapshot.updatedAt)
        assertNull(snapshot.observedAt)
        assertFalse(snapshot.metrics.isEmpty())
        assertTrue(snapshot.metrics.all { it.source == DataSource.DEMO })
        assertTrue(snapshot.areaLabel.contains("示例"))
        assertTrue(snapshot.areaLabel.contains("未定位"))
    }

    @Test
    fun terrainElevationRemainsUnavailableInsteadOfUsingAFabricatedNumber() = runBlocking {
        val service = DemoEnvironmentDataService(Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
        val altitude = service.fetch().metrics.single { it.id == "altitude" }
        assertEquals("—", altitude.value)
        assertEquals("m", altitude.unit)
        assertEquals(DataSource.DEMO, altitude.source)
    }
}

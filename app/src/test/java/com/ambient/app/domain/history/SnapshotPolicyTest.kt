package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class SnapshotPolicyTest {
    private val snapshot = EnvironmentSnapshot("测试区域", Instant.parse("2026-10-16T08:00:00Z"),
        listOf(EnvironmentMetric("temperature", "室外温度", "20.1", "°C", DataSource.REGIONAL_API, "区域数据")), isDemo = false)
    @Test fun onlyRealRegionalDataCanBecomeARecord() {
        assertNull(SnapshotPolicy.unavailableReason(snapshot))
        assertNotNull(SnapshotPolicy.unavailableReason(null))
        assertNotNull(SnapshotPolicy.unavailableReason(snapshot.copy(isDemo = true)))
        assertNotNull(SnapshotPolicy.unavailableReason(snapshot.copy(areaLabel = " ")))
    }
    @Test fun mixedDemoMetricsAreRejectedEvenWhenSnapshotFlagIsFalse() {
        val mixed = snapshot.copy(metrics = snapshot.metrics + snapshot.metrics.first().copy(id = "humidity", source = DataSource.DEMO))
        assertThrows(HistoryException::class.java) { SnapshotPolicy.validate(mixed) }
    }
    @Test fun missingRegionalValuesAndDeviceOnlyDataAreNotAccepted() {
        for (value in listOf("", " ", "—", "--"))
            assertNotNull(SnapshotPolicy.unavailableReason(snapshot.copy(metrics = listOf(snapshot.metrics.first().copy(value = value)))))
        assertNotNull(SnapshotPolicy.unavailableReason(snapshot.copy(metrics = listOf(snapshot.metrics.first().copy(source = DataSource.DEVICE)))))
    }
    @Test fun partiallyAvailableRegionalDataIsPreservedWithoutFillingMissingValues() {
        val partial = snapshot.copy(metrics = snapshot.metrics + snapshot.metrics.first().copy(id = "aqi", value = "—"))
        assertNull(SnapshotPolicy.unavailableReason(partial))
        assertEquals("—", partial.metrics.last().value)
    }
    @Test fun notesAreBoundedAndBlankNotesAreAllowed() {
        assertEquals("", SnapshotPolicy.normalizeNote("   "))
        assertEquals("散步", SnapshotPolicy.normalizeNote(" 散步 "))
        assertEquals(300, SnapshotPolicy.normalizeNote("a".repeat(300)).length)
        assertThrows(HistoryException::class.java) { SnapshotPolicy.normalizeNote("a".repeat(301)) }
    }
}

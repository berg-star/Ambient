package com.ambient.app.domain.environment

import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryCsvWriter
import com.ambient.app.domain.history.HistoryComparisonBuilder
import com.ambient.app.ui.overview.EnvironmentUiState
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class PositioningHeightMetricTest {
    private val at = Instant.parse("2026-10-17T03:00:00Z")
    private val area = EnvironmentArea("测试地点", 30.0, 120.0, fromDevice = true,
        positioningHeightMeters = 123.4, verticalAccuracyMeters = 15.0, positioningHeightAtMillis = at.toEpochMilli())
    private val weather = EnvironmentSnapshot(area.label, at.plusSeconds(60), listOf(
        EnvironmentMetric("temperature", "室外温度", "20", "°C", DataSource.REGIONAL_API, "区域数据"),
        EnvironmentMetric("altitude", "地形海拔", "—", "m", DataSource.ESTIMATED, "未接入")))

    @Test fun validHeightIsExplicitlyPositioningNotWeatherOrDirectSensor() {
        val metric = positioningHeightMetric(area)
        assertEquals("近似定位高度", metric.label); assertEquals("123", metric.value)
        assertEquals(DataSource.POSITIONING, metric.source)
        assertTrue(metric.description.contains("±15 m")); assertTrue(metric.description.contains(at.toString()))
        assertTrue(metric.description.contains("上次单次定位")); assertTrue(metric.description.contains("WGS84"))
    }
    @Test fun missingOrInvalidHeightNeverBecomesAZeroReading() {
        for (value in listOf(null, Double.NaN, Double.POSITIVE_INFINITY, -501.0, 10001.0))
            assertEquals("—", positioningHeightMetric(area.copy(positioningHeightMeters = value)).value)
    }
    @Test fun actualZeroAndBelowSeaLevelHeightsRemainValid() {
        assertEquals("0", positioningHeightMetric(area.copy(positioningHeightMeters = 0.0)).value)
        assertEquals("-33", positioningHeightMetric(area.copy(positioningHeightMeters = -32.8)).value)
    }
    @Test fun manualCityCannotReuseThePhonesHeight() {
        assertEquals("—", positioningHeightMetric(area.copy(fromDevice = false)).value)
        assertEquals("—", positioningHeightMetric(null).value)
    }
    @Test fun absentPrecisionAndOldVersionMissingTimeAreHonest() {
        val metric = positioningHeightMetric(area.copy(verticalAccuracyMeters = null, positioningHeightAtMillis = null))
        assertTrue(metric.description.contains("未提供垂直精度")); assertTrue(metric.description.contains("时间未记录"))
        assertFalse(metric.description.contains("±"))
    }
    @Test fun simulatedHeightHasPersistentExplicitTestMetadata() {
        val metric = positioningHeightMetric(area.copy(isMock = true))
        assertTrue(metric.isSimulated); assertTrue(metric.description.contains("非真实手机高度"))
    }
    @Test fun projectionLeavesOriginalApiCacheAndWeatherTimesIntact() {
        val projected = weather.withPositioningHeight(area)
        assertEquals("—", weather.metrics.last().value)
        assertEquals(weather.updatedAt, projected.updatedAt); assertEquals(weather.observedAt, projected.observedAt)
        assertEquals("123", projected.metrics.last().value)
        assertEquals(1, projected.metrics.count { it.id == "altitude" })
    }
    @Test fun demoAndWrongAreaCannotAcquireAnotherLocationsHeight() {
        val demo = weather.copy(isDemo = true)
        assertSame(demo, demo.withPositioningHeight(area))
        assertEquals("—", weather.withPositioningHeight(area.copy(label = "另一个地点")).metrics.last().value)
    }
    @Test fun uiCanShowHeightEvenIfWeatherIsUnavailable() {
        val state = EnvironmentUiState(preferences = EnvironmentPreferences(true, true, area))
        assertNull(state.displaySnapshot); assertEquals("123", state.heightWithoutWeather.value)
        assertEquals("—", state.copy(preferences = state.preferences.copy(enabled = false)).heightWithoutWeather.value)
    }
    @Test fun snapshotAndCsvPreserveHeightSourceTimeAndSimulationFlag() {
        val snapshot = weather.withPositioningHeight(area.copy(isMock = true))
        assertEquals(snapshot, SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
        val record = EnvironmentRecord("height-test", at, false, snapshot, "")
        val csv = StringWriter().also { HistoryCsvWriter.writeRecord(it, record) }.toString()
        assertTrue(csv.contains("POSITIONING")); assertTrue(csv.contains("模拟定位")); assertTrue(csv.contains(at.toString()))
    }
    @Test fun oldSnapshotsWithoutNewMetadataStillDecode() {
        val encoded = SnapshotCodec.encode(weather).replace(",\"isSimulated\":false", "")
        assertEquals(weather, SnapshotCodec.decode(encoded))
        assertFalse(SnapshotCodec.decode(encoded).metrics.any { it.isSimulated })
    }
    @Test fun heightDoesNotBecomeARegionalTrendOrDifference() {
        val a = EnvironmentRecord("a", at, false, weather.withPositioningHeight(area), "")
        val b = a.copy(id = "b", snapshot = weather.copy(updatedAt = at.plusSeconds(120))
            .withPositioningHeight(area.copy(positioningHeightMeters = 456.0)))
        val row = HistoryComparisonBuilder.build(a, b).metrics.first { it.id == "altitude" }
        assertNull(row.delta)
    }
}

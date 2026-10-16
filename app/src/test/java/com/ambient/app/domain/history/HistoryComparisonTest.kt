package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryComparisonTest {
    private val time = Instant.parse("2026-10-16T08:00:00Z")
    private val temperature = EnvironmentMetric("temperature", "室外温度", "20.1", "°C", DataSource.REGIONAL_API, "原始来源说明")

    private fun record(
        id: String = "a",
        metrics: List<EnvironmentMetric> = listOf(temperature),
        fetchedAt: Instant = if (id == "a") time else time.plusSeconds(60),
        area: String = "北京市 · 北京",
        cached: Boolean = false,
        isDemo: Boolean = false,
    ) = EnvironmentRecord(id, fetchedAt.plusSeconds(5), cached, EnvironmentSnapshot(
        area, fetchedAt, metrics, fetchedAt.minusSeconds(30), listOf("原始服务商"), listOf("原始通知"), isDemo,
    ), "原始备注")

    @Test fun validTemperatureDifferenceIsBMinusAAndPreservesBothOriginalRecords() {
        val a = record()
        val bMetric = temperature.copy(value = "23.6", description = "B 的原始说明")
        val b = record("b", listOf(bMetric))
        val result = HistoryComparisonBuilder.build(a, b)
        assertSame(a, result.left)
        assertSame(b, result.right)
        val metric = result.metrics.single()
        assertSame(temperature, metric.left)
        assertSame(bMetric, metric.right)
        assertEquals(3.5, metric.delta!!, 0.000001)
        assertEquals("°C", metric.deltaUnit)
        assertNull(metric.explanation)
        assertNull(result.notice)
        assertEquals(-3.5, HistoryComparisonBuilder.build(b, a).metrics.single().delta!!, 0.000001)
    }

    @Test fun negativeTemperaturesRemainValidAndDatesAreNotSilentlyReordered() {
        val a = record(metrics = listOf(temperature.copy(value = "-10.5")))
        val b = record("b", listOf(temperature.copy(value = "-13.0")))
        val result = HistoryComparisonBuilder.build(a, b)
        assertEquals(-2.5, result.metrics.single().delta!!, 0.0)
        assertSame(a, result.left)
        assertSame(b, result.right)
    }

    @Test fun humidityDifferenceUsesPercentagePointsRatherThanRelativePercent() {
        val humidity = temperature.copy(id = "humidity", label = "相对湿度", value = "50", unit = "%")
        val result = HistoryComparisonBuilder.build(record(metrics = listOf(humidity)), record("b", listOf(humidity.copy(value = "60"))))
        assertEquals(10.0, result.metrics.single().delta!!, 0.0)
        assertEquals("个百分点", result.metrics.single().deltaUnit)
        assertEquals("%", result.metrics.single().left!!.unit)
        assertEquals("%", result.metrics.single().right!!.unit)
    }

    @Test fun differentAreasShowOriginalValuesButNoTimeChange() {
        val a = record()
        val b = record("b", listOf(temperature.copy(value = "25")), area = "上海市 · 上海")
        val result = HistoryComparisonBuilder.build(a, b)
        assertNull(result.metrics.single().delta)
        assertEquals("20.1", result.metrics.single().left!!.value)
        assertEquals("25", result.metrics.single().right!!.value)
        assertTrue(result.notice!!.contains("不同区域"))
        assertTrue(result.metrics.single().explanation!!.contains("区域不同"))
    }

    @Test fun sameFetchIncludingRepeatedCacheSavesIsNotANewObservation() {
        for (cached in listOf(false, true)) {
            val a = record()
            val b = record("b", listOf(temperature.copy(value = "25")), fetchedAt = time, cached = cached)
            val result = HistoryComparisonBuilder.build(a, b)
            assertNull(result.metrics.single().delta)
            assertTrue(result.notice!!.contains("不是新观测"))
        }
    }

    @Test fun blankAreaLabelsCannotEstablishTheSamePhysicalLocation() {
        val result = HistoryComparisonBuilder.build(record(area = ""), record("b", area = ""))
        assertNull(result.metrics.single().delta)
        assertTrue(result.notice!!.contains("区域信息缺失"))
    }

    @Test fun cachedRecordFromAnotherOriginalFetchCanStillBeCompared() {
        val result = HistoryComparisonBuilder.build(record(cached = true), record("b", listOf(temperature.copy(value = "21.1")), cached = true))
        assertEquals(1.0, result.metrics.single().delta!!, 0.000001)
        assertTrue(result.left.wasCached)
        assertTrue(result.right.wasCached)
        assertNull(result.notice)
    }

    @Test fun snapshotDemoFlagsAndMixedDemoMetricsPreventAllDifferences() {
        val variants = listOf(
            record(isDemo = true),
            record(metrics = listOf(temperature.copy(source = DataSource.DEMO))),
            record(metrics = listOf(temperature, temperature.copy(id = "demo-other", source = DataSource.DEMO))),
        )
        for (a in variants) {
            val result = HistoryComparisonBuilder.build(a, record("b"))
            assertNull(result.metrics.first().delta)
            assertTrue(result.notice!!.contains("演示数据"))
        }
    }

    @Test fun missingInvalidAndNonFiniteReadingsNeverBecomeZero() {
        for (value in listOf("—", "--", "", " ", "约 20", "NaN", "Infinity", "-Infinity", "1e309")) {
            val result = HistoryComparisonBuilder.build(record(), record("b", listOf(temperature.copy(value = value))))
            assertNull(value, result.metrics.single().delta)
            assertNotNull(result.metrics.single().explanation)
            assertEquals(value, result.metrics.single().right!!.value)
        }
    }

    @Test fun unitMismatchAndNonRegionalSourcesPreventDifference() {
        val variants = listOf(temperature.copy(unit = "K"), temperature.copy(unit = "°C "),
            temperature.copy(source = DataSource.DEVICE), temperature.copy(source = DataSource.ESTIMATED))
        for (measurement in variants) {
            val result = HistoryComparisonBuilder.build(record(), record("b", listOf(measurement)))
            assertNull(result.metrics.single().delta)
            assertNotNull(result.metrics.single().explanation)
            assertSame(measurement, result.metrics.single().right)
        }
    }

    @Test fun differentAqiLabelsDoNotAssumeTheSameStandard() {
        val aqi = temperature.copy(id = "aqi", label = "空气质量 · 中国", value = "50", unit = "AQI")
        val result = HistoryComparisonBuilder.build(record(metrics = listOf(aqi)), record("b", listOf(aqi.copy(label = "空气质量 · 美国", value = "60"))))
        assertNull(result.metrics.single().delta)
        assertTrue(result.metrics.single().explanation!!.contains("标准"))
    }

    @Test fun unionKeepsAOrderThenBOnlyMetricsAndMissingValuesStayMissing() {
        val humidity = temperature.copy(id = "humidity", label = "相对湿度", value = "50", unit = "%")
        val pm25 = temperature.copy(id = "pm25", label = "PM2.5", value = "12", unit = "μg/m³")
        val result = HistoryComparisonBuilder.build(record(metrics = listOf(humidity, temperature)), record("b", listOf(temperature.copy(value = "21"), pm25)))
        assertEquals(listOf("humidity", "temperature", "pm25"), result.metrics.map { it.id })
        assertNull(result.metrics.first().right)
        assertNull(result.metrics.first().delta)
        assertNull(result.metrics.last().left)
        assertNull(result.metrics.last().delta)
        assertSame(humidity, result.metrics.first().left)
        assertSame(pm25, result.metrics.last().right)
    }

    @Test fun unknownMetricsAndWindBearingRemainSideBySideWithoutInventedDifference() {
        val wind = temperature.copy(id = "windDirection", label = "风向", value = "东北", unit = "", windFromDegrees = 45.0)
        val unknown = temperature.copy(id = "future-scalar", value = "10")
        val a = record(metrics = listOf(wind, unknown))
        val bWind = wind.copy(value = "西南", windFromDegrees = 225.0)
        val result = HistoryComparisonBuilder.build(a, record("b", listOf(bWind, unknown.copy(value = "20"))))
        assertTrue(result.metrics.all { it.delta == null && it.explanation != null })
        assertSame(wind, result.metrics[0].left)
        assertSame(bWind, result.metrics[0].right)
        assertEquals(225.0, result.metrics[0].right!!.windFromDegrees!!, 0.0)
    }

    @Test fun allKnownScalarIdsUseTheirOriginalUnitAndFiniteAbsoluteDifference() {
        for ((id, unit) in listOf("pressure" to "hPa", "aqi" to "AQI", "pm25" to "μg/m³", "pm10" to "μg/m³", "windSpeed" to "m/s", "uv" to "UVI")) {
            val measurement = temperature.copy(id = id, value = "10", unit = unit)
            val result = HistoryComparisonBuilder.build(record(metrics = listOf(measurement)), record("b", listOf(measurement.copy(value = "12"))))
            assertEquals(id, 2.0, result.metrics.single().delta!!, 0.0)
            assertEquals(unit, result.metrics.single().deltaUnit)
        }
    }

    @Test fun physicallyInvalidReadingsAndOverflowDoNotProduceDifferences() {
        val measurements = listOf(
            temperature.copy(value = "-273.16"),
            temperature.copy(id = "humidity", value = "100.1", unit = "%"),
            temperature.copy(id = "pressure", value = "0", unit = "hPa"),
            temperature.copy(id = "aqi", value = "-1", unit = "AQI"),
            temperature.copy(id = "pm25", value = "-1", unit = "μg/m³"),
            temperature.copy(id = "windSpeed", value = "-1", unit = "m/s"),
            temperature.copy(id = "uv", value = "-1", unit = "UVI"),
        )
        for (measurement in measurements) {
            val result = HistoryComparisonBuilder.build(record(metrics = listOf(measurement)), record("b", listOf(measurement.copy(value = "10"))))
            assertNull(measurement.id, result.metrics.single().delta)
            assertNotNull(result.metrics.single().explanation)
        }
        val a = temperature.copy(value = "-1.0e308", unit = "test-unit")
        val b = a.copy(value = "1.0e308")
        val overflow = HistoryComparisonBuilder.build(record(metrics = listOf(a)), record("b", listOf(b))).metrics.single()
        assertNull(overflow.delta)
        assertTrue(overflow.explanation!!.contains("范围"))
    }

    @Test fun emptySnapshotsRemainEmptyAndGenuineZerosRemainMeasurements() {
        assertTrue(HistoryComparisonBuilder.build(record(metrics = emptyList()), record("b", metrics = emptyList())).metrics.isEmpty())
        val zero = temperature.copy(value = "0")
        val result = HistoryComparisonBuilder.build(record(metrics = listOf(zero)), record("b", listOf(zero.copy(value = "-0.0"))))
        assertEquals(0.0, result.metrics.single().delta!!, 0.0)
        assertNull(result.metrics.single().explanation)
        assertEquals("-0.0", result.metrics.single().right!!.value)
    }
}

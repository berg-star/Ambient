package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

class HistoryAnalysisTest {
    private val zone = ZoneId.of("Asia/Shanghai")
    private val time = Instant.parse("2026-10-16T08:00:00Z")
    private val area = "北京市 · 北京"
    private val temperature = EnvironmentMetric("temperature", "室外温度", "20.1", "°C", DataSource.REGIONAL_API, "原始来源说明")

    private fun record(
        id: String = "record",
        fetchedAt: Instant = time,
        savedAt: Instant = fetchedAt.plusSeconds(5),
        cached: Boolean = false,
        areaLabel: String = area,
        measurement: EnvironmentMetric? = temperature,
        isDemo: Boolean = false,
        observedAt: Instant? = fetchedAt.minusSeconds(30),
    ) = EnvironmentRecord(id, savedAt, cached, EnvironmentSnapshot(
        areaLabel, fetchedAt, listOfNotNull(measurement), observedAt,
        attributions = listOf("原始服务商"), notices = listOf("原始通知"), isDemo = isDemo,
    ), "原始备注")

    private fun accumulator(
        metric: TrendMetric = TrendMetric.TEMPERATURE,
        maxPoints: Int = 120,
        filter: HistoryFilter = HistoryFilter(area),
    ) = HistoryTrendAccumulator(filter, metric, zone, maxPoints)

    @Test fun dateFilterIncludesBothLocalDaysAndExcludesNextMidnight() {
        val filter = HistoryFilter(area, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 17))
        val bounds = filter.bounds(zone)
        assertEquals(Instant.parse("2026-10-15T16:00:00Z"), bounds.fromInclusive)
        assertEquals(Instant.parse("2026-10-17T16:00:00Z"), bounds.untilExclusive)
        assertTrue(filter.includes(area, bounds.fromInclusive!!, zone))
        assertTrue(filter.includes(area, bounds.untilExclusive!!.minusNanos(1), zone))
        assertFalse(filter.includes(area, bounds.fromInclusive.minusNanos(1), zone))
        assertFalse(filter.includes(area, bounds.untilExclusive, zone))
        assertFalse(filter.includes("上海市 · 上海", time, zone))
    }

    @Test fun dateBoundsUseCalendarDaysAcrossBothDstTransitions() {
        val newYork = ZoneId.of("America/New_York")
        val spring = HistoryFilter(fromDate = LocalDate.of(2026, 3, 8), toDate = LocalDate.of(2026, 3, 8)).bounds(newYork)
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"), spring.fromInclusive)
        assertEquals(Instant.parse("2026-03-09T04:00:00Z"), spring.untilExclusive)
        assertEquals(23, Duration.between(spring.fromInclusive, spring.untilExclusive).toHours())
        val autumn = HistoryFilter(fromDate = LocalDate.of(2026, 11, 1), toDate = LocalDate.of(2026, 11, 1)).bounds(newYork)
        assertEquals(25, Duration.between(autumn.fromInclusive, autumn.untilExclusive).toHours())
    }

    @Test fun filtersAllowUnboundedAndOneSidedDates() {
        assertEquals(HistoryBounds(null, null), HistoryFilter().bounds(zone))
        assertTrue(HistoryFilter().includes("任意区域", time, zone))
        assertNull(HistoryFilter(fromDate = LocalDate.of(2026, 10, 16)).bounds(zone).untilExclusive)
        assertNull(HistoryFilter(toDate = LocalDate.of(2026, 10, 16)).bounds(zone).fromInclusive)
        assertThrows(IllegalArgumentException::class.java) {
            HistoryFilter(fromDate = LocalDate.of(2026, 10, 17), toDate = LocalDate.of(2026, 10, 16))
        }
    }

    @Test fun accumulatorRequiresOneNonblankAreaAndPositiveBound() {
        assertThrows(IllegalArgumentException::class.java) { accumulator(filter = HistoryFilter()) }
        assertThrows(IllegalArgumentException::class.java) { accumulator(filter = HistoryFilter(" ")) }
        assertThrows(IllegalArgumentException::class.java) { accumulator(maxPoints = 0) }
    }

    @Test fun differentAreasAndDemoSnapshotsNeverEnterTrend() {
        val trend = accumulator()
        trend.add(record(id = "other", areaLabel = "上海市 · 上海"))
        trend.add(record(id = "demo", isDemo = true))
        trend.add(record(id = "real"))
        assertEquals(listOf("real"), trend.build().points.map { it.recordId })
        assertEquals(0, trend.build().duplicateCount)
    }

    @Test fun mixedDemoMetricsAreIgnoredEvenWhenSnapshotFlagIsFalse() {
        val original = record()
        val mixed = original.copy(snapshot = original.snapshot.copy(
            metrics = listOf(temperature, temperature.copy(id = "humidity", source = DataSource.DEMO)),
            isDemo = false,
        ))
        val trend = accumulator().apply { add(mixed) }.build()
        assertTrue(trend.points.isEmpty())
        assertEquals(0, trend.duplicateCount)
    }

    @Test fun accumulatorAppliesFetchedDateRatherThanSaveDate() {
        val trend = accumulator(filter = HistoryFilter(area, LocalDate.of(2026, 10, 16), LocalDate.of(2026, 10, 16)))
        trend.add(record(id = "saved-later", savedAt = time.plusSeconds(172_800)))
        trend.add(record(id = "fetched-before", fetchedAt = Instant.parse("2026-10-15T15:59:59Z"), savedAt = time))
        trend.add(record(id = "next-midnight", fetchedAt = Instant.parse("2026-10-16T16:00:00Z"), savedAt = time))
        assertEquals(listOf("saved-later"), trend.build().points.map { it.recordId })
    }

    @Test fun repeatedCachesCollapseAndFreshCopyWinsRegardlessOfSaveTimeOrVisitOrder() {
        val records = listOf(
            record(id = "cache-1", cached = true, savedAt = time.plusSeconds(1)),
            record(id = "fresh", savedAt = time.plusSeconds(30)),
            record(id = "cache-2", cached = true, savedAt = time.plusSeconds(60)),
        )
        for (order in listOf(records, records.reversed())) {
            val trend = accumulator().apply { order.forEach(::add) }.build()
            assertEquals(1, trend.points.size)
            assertEquals("fresh", trend.points.single().recordId)
            assertFalse(trend.points.single().wasCached)
            assertEquals(2, trend.duplicateCount)
        }
    }

    @Test fun cachedOnlyPointIsKeptAndTieBreaksAreDeterministic() {
        val trend = accumulator()
        trend.add(record(id = "later", cached = true, savedAt = time.plusSeconds(10)))
        trend.add(record(id = "b", cached = true, savedAt = time.plusSeconds(2)))
        trend.add(record(id = "a", cached = true, savedAt = time.plusSeconds(2)))
        val point = trend.build().points.single()
        assertEquals("a", point.recordId)
        assertTrue(point.wasCached)
        assertEquals(time.plusSeconds(2), point.savedAt)
        assertEquals(2, trend.build().duplicateCount)
    }

    @Test fun selectedPointPreservesOriginalMetricMetadataAndTimes() {
        val original = record(cached = true, fetchedAt = time.plusNanos(123), observedAt = time.minusSeconds(11))
        val point = accumulator().apply { add(original) }.build().points.single()
        assertEquals(original.id, point.recordId)
        assertEquals(area, point.areaLabel)
        assertEquals(original.snapshot.updatedAt, point.fetchedAt)
        assertEquals(original.savedAt, point.savedAt)
        assertEquals(original.snapshot.observedAt, point.observedAt)
        assertSame(temperature, point.metric)
        assertEquals(20.1, point.value!!, 0.0)
        assertNull(point.missingReason)
    }

    @Test fun missingMetricAndMissingValuesRemainExplicitGaps() {
        val measurements = listOf(null, temperature.copy(value = ""), temperature.copy(value = "  "),
            temperature.copy(value = "—"), temperature.copy(value = "--"))
        for (measurement in measurements) {
            val point = accumulator().apply { add(record(measurement = measurement)) }.build().points.single()
            assertNull(point.value)
            assertNotNull(point.missingReason)
            assertEquals(measurement, point.metric)
        }
    }

    @Test fun nonNumericAndNonFiniteValuesAreNotDrawnAsZero() {
        for (value in listOf("约 20", "NaN", "Infinity", "-Infinity", "1e309")) {
            val point = accumulator().apply { add(record(measurement = temperature.copy(value = value))) }.build().points.single()
            assertNull("$value must be a gap", point.value)
            assertNotNull(point.missingReason)
        }
    }

    @Test fun incompatibleUnitsAndNonRegionalSourcesRemainGaps() {
        val measurements = listOf(temperature.copy(unit = "K"), temperature.copy(unit = "°C "),
            temperature.copy(source = DataSource.DEVICE), temperature.copy(source = DataSource.ESTIMATED))
        for (measurement in measurements) {
            val point = accumulator().apply { add(record(measurement = measurement, isDemo = false)) }.build().points.single()
            assertNull(point.value)
            assertNotNull(point.missingReason)
            assertEquals(measurement, point.metric)
        }
    }

    @Test fun physicalRangesAreValidatedForEachMetric() {
        val invalid = mapOf(
            TrendMetric.TEMPERATURE to listOf("-273.16"),
            TrendMetric.HUMIDITY to listOf("-0.1", "100.1"),
            TrendMetric.PRESSURE to listOf("0", "-1"),
            TrendMetric.AQI to listOf("-1"),
        )
        for ((metric, values) in invalid) for (value in values) {
            val measurement = temperature.copy(id = metric.metricId, value = value, unit = metric.unit)
            val point = accumulator(metric).apply { add(record(measurement = measurement)) }.build().points.single()
            assertNull("${metric.name}: $value", point.value)
            assertNotNull(point.missingReason)
        }
    }

    @Test fun validZeroEndpointsAndNegativeTemperatureRemainMeasurements() {
        val valid = mapOf(
            TrendMetric.TEMPERATURE to listOf("-10.5", "-273.15", "0"),
            TrendMetric.HUMIDITY to listOf("0", "100"),
            TrendMetric.PRESSURE to listOf("0.01", "1013.25"),
            TrendMetric.AQI to listOf("0", "42"),
        )
        for ((metric, values) in valid) for (value in values) {
            val measurement = temperature.copy(id = metric.metricId, value = value, unit = metric.unit)
            val point = accumulator(metric).apply { add(record(measurement = measurement)) }.build().points.single()
            assertEquals(value.toDouble(), point.value!!, 0.0)
            assertNull(point.missingReason)
        }
    }

    @Test fun chronologicalOrderUsesExactFetchInstantsAndKeepsGaps() {
        val trend = accumulator()
        trend.add(record(id = "third", fetchedAt = time.plusSeconds(5)))
        trend.add(record(id = "first", fetchedAt = time))
        trend.add(record(id = "gap", fetchedAt = time.plusNanos(1), measurement = null))
        assertEquals(listOf("first", "gap", "third"), trend.build().points.map { it.recordId })
        assertNull(trend.build().points[1].value)
        assertFalse(trend.build().hasOlder)
    }

    @Test fun emptyAndSinglePointTrendsNeedNoInventedExtraPoint() {
        val trend = accumulator()
        assertTrue(trend.build().points.isEmpty())
        assertEquals(area, trend.build().areaLabel)
        assertEquals(TrendMetric.TEMPERATURE, trend.build().metric)
        assertFalse(trend.build().hasOlder)
        trend.add(record(observedAt = null))
        assertEquals(1, trend.build().points.size)
        assertNull(trend.build().points.single().observedAt)
    }

    @Test fun arbitraryVisitOrderKeepsLatest120UniqueFetchesAndCountsOnlyRetainedDuplicates() {
        val records = (0 until 200).flatMap { index ->
            listOf(record(id = "fresh-$index", fetchedAt = time.plusSeconds(index.toLong())),
                record(id = "cache-$index", fetchedAt = time.plusSeconds(index.toLong()), cached = true))
        }
        val expected = (80 until 200).map { "fresh-$it" }
        for (order in listOf(records, records.reversed(), records.shuffled(kotlin.random.Random(42)))) {
            val trend = accumulator().apply { order.forEach(::add) }.build()
            assertEquals(expected, trend.points.map { it.recordId })
            assertEquals(120, trend.points.size)
            assertEquals(120, trend.duplicateCount)
            assertTrue(trend.hasOlder)
        }
    }

    @Test fun repeatedTimestampDoesNotConsumeCapacityAndBuildReturnsIndependentList() {
        val trend = accumulator(maxPoints = 2)
        trend.add(record(id = "first"))
        val earlierBuild = trend.build()
        repeat(10) { trend.add(record(id = "cache-$it", cached = true)) }
        trend.add(record(id = "second", fetchedAt = time.plusSeconds(1)))
        assertEquals(2, trend.build().points.size)
        assertEquals(10, trend.build().duplicateCount)
        assertFalse(trend.build().hasOlder)
        assertEquals(listOf("first"), earlierBuild.points.map { it.recordId })
    }
}

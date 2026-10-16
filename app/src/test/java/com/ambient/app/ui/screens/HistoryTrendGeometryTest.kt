package com.ambient.app.ui.screens

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryTrendGeometryTest {
    private val start = Instant.parse("2026-10-16T00:00:00Z")

    @Test fun irregularAcquisitionTimesKeepTheirRealHorizontalSpacing() {
        val end = start.plusSeconds(3600)
        assertEquals(0f, HistoryTrendGeometry.xFraction(start, end, start), 0f)
        assertEquals(1f / 60, HistoryTrendGeometry.xFraction(start, end, start.plusSeconds(60)), .00001f)
        assertEquals(.5f, HistoryTrendGeometry.xFraction(start, end, start.plusSeconds(1800)), 0f)
        assertEquals(1f, HistoryTrendGeometry.xFraction(start, end, end), 0f)
    }

    @Test fun subSecondTimesAndSinglePointDoNotDependOnUiFrameTime() {
        assertEquals(.25f, HistoryTrendGeometry.xFraction(start, start.plusNanos(400), start.plusNanos(100)), .0001f)
        assertEquals(.5f, HistoryTrendGeometry.xFraction(start, start, start), 0f)
    }

    @Test fun constantValuesHaveAVisibleRangeAndStayCentered() {
        for (value in listOf(0.0, -20.0, 1013.0)) {
            val range = HistoryTrendGeometry.yRange(listOf(value, value))
            assertTrue(range.start < value)
            assertTrue(range.endInclusive > value)
            assertEquals(.5f, HistoryTrendGeometry.yFraction(value, range), .0001f)
        }
    }

    @Test fun missingValuesNeverSetTheRangeToZeroAndEmptyValuesHaveSafeDefaults() {
        val range = HistoryTrendGeometry.yRange(listOf(20.0, 30.0, Double.NaN, Double.POSITIVE_INFINITY))
        assertEquals(20.0, range.start, 0.0)
        assertEquals(30.0, range.endInclusive, 0.0)
        assertEquals(0.0..1.0, HistoryTrendGeometry.yRange(emptyList()))
        assertEquals(0f, HistoryTrendGeometry.yFraction(20.0, range), 0f)
        assertEquals(1f, HistoryTrendGeometry.yFraction(30.0, range), 0f)
    }

    @Test fun largeFiniteRangesRemainFiniteAndProportional() {
        val range = -Double.MAX_VALUE..Double.MAX_VALUE
        assertEquals(.5f, HistoryTrendGeometry.yFraction(0.0, range), 0f)
        assertEquals(1f, HistoryTrendGeometry.yFraction(Double.MAX_VALUE, range), 0f)
        assertTrue(HistoryTrendGeometry.yRange(listOf(Double.MAX_VALUE)).start.isFinite())
    }

    @Test fun extremeConstantsAndSubnormalRangesProduceOnlyFiniteCanvasCoordinates() {
        for (value in listOf(-Double.MAX_VALUE, Double.MAX_VALUE, Double.MIN_VALUE, -Double.MIN_VALUE)) {
            val range = HistoryTrendGeometry.yRange(listOf(value))
            assertTrue(range.start.isFinite())
            assertTrue(range.endInclusive.isFinite())
            assertTrue(value in range)
            val fraction = HistoryTrendGeometry.yFraction(value, range)
            assertTrue(fraction.isFinite())
            assertTrue(fraction in 0f..1f)
        }
        val tiny = 0.0..(Double.MIN_VALUE * 2)
        assertEquals(.5f, HistoryTrendGeometry.yFraction(Double.MIN_VALUE, tiny), 0f)
    }

    @Test fun outOfRangeAndInvalidInputsNeverReachCanvasAsInfinityOrNan() {
        assertEquals(1f, HistoryTrendGeometry.yFraction(Double.MAX_VALUE, -Double.MAX_VALUE..-1.0), 0f)
        assertEquals(0f, HistoryTrendGeometry.yFraction(-Double.MAX_VALUE, 1.0..Double.MAX_VALUE), 0f)
        for (value in listOf(Double.NaN, Double.NEGATIVE_INFINITY, Double.POSITIVE_INFINITY)) {
            assertEquals(.5f, HistoryTrendGeometry.yFraction(value, 0.0..1.0), 0f)
        }
        assertEquals(.5f, HistoryTrendGeometry.yFraction(1.0, Double.NEGATIVE_INFINITY..Double.POSITIVE_INFINITY), 0f)
        assertEquals(.5f, HistoryTrendGeometry.yFraction(1.0, 2.0..-2.0), 0f)
    }

    @Test fun fullInstantRangeDoesNotOverflowAnIntegerNanosecondConversion() {
        assertEquals(0f, HistoryTrendGeometry.xFraction(Instant.MIN, Instant.MAX, Instant.MIN), 0f)
        assertEquals(.5f, HistoryTrendGeometry.xFraction(Instant.MIN, Instant.MAX, Instant.EPOCH), .0001f)
        assertEquals(1f, HistoryTrendGeometry.xFraction(Instant.MIN, Instant.MAX, Instant.MAX), 0f)
    }
}

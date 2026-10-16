package com.ambient.app.data.sensor

import com.ambient.app.domain.sensor.SensorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorReadingAccumulatorTest {
    @Test
    fun vectorReadingRetainsAxesAndComputesMagnitude() {
        val result = SensorReadingAccumulator(SensorKind.MAGNETIC_FIELD)
            .accept(floatArrayOf(3f, 4f, 12f), 123L, 3) as SensorUpdate.Published
        assertEquals(listOf(3f, 4f, 12f), result.reading.values)
        assertEquals(13f, result.reading.magnitude!!, 0.0001f)
        assertEquals(123L, result.reading.samples.single().timestampNanos)
        assertEquals(13f, result.reading.samples.single().value, 0.0001f)
    }

    @Test
    fun linearAccelerationPreservesSystemAxesAndDoesNotSubtractGravityAgain() {
        val result = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
            .accept(floatArrayOf(-0.03f, 0.04f, 0f), 123L, 3) as SensorUpdate.Published
        assertEquals(listOf(-0.03f, 0.04f, 0f), result.reading.values)
        assertEquals(0.05f, result.reading.magnitude!!, 0.0001f)
        assertEquals(123L, result.reading.timestampNanos)
        assertEquals(0.05f, result.reading.samples.single().value, 0.0001f)
    }

    @Test
    fun throttlingUsesHardwareTimeAndRejectsOlderEvents() {
        val accumulator = SensorReadingAccumulator(SensorKind.PRESSURE)
        assertTrue(accumulator.accept(floatArrayOf(1f), 1L, 3) is SensorUpdate.Published)
        assertTrue(accumulator.accept(floatArrayOf(2f), 50_000_001L, 3) is SensorUpdate.Ignored)
        assertTrue(accumulator.accept(floatArrayOf(3f), 40_000_001L, 3) is SensorUpdate.Ignored)
        val result = accumulator.accept(floatArrayOf(4f), 100_000_001L, 3) as SensorUpdate.Published
        assertEquals(listOf(1f, 4f), result.reading.samples.map { it.value })
        assertEquals(100_000_001L, result.reading.timestampNanos)
    }

    @Test
    fun historyIsBoundedAndSnapshotsAreIndependent() {
        val accumulator = SensorReadingAccumulator(SensorKind.PRESSURE)
        val first = accumulator.accept(floatArrayOf(1000f), 1L, 3) as SensorUpdate.Published
        var latest = first
        repeat(90) { index ->
            latest = accumulator.accept(
                floatArrayOf(1001f + index), (index + 1L) * 100_000_000L + 1L, 3,
            ) as SensorUpdate.Published
        }
        assertEquals(60, latest.reading.samples.size)
        assertEquals(1, first.reading.samples.size)
        assertEquals(1031f, latest.reading.samples.first().value, 0f)
        assertEquals(null, latest.reading.magnitude)
    }

    @Test
    fun invalidReadingsClearHistoryAndNeverBecomeMeasurements() {
        val accumulator = SensorReadingAccumulator(SensorKind.ACCELEROMETER)
        accumulator.accept(floatArrayOf(0f, 0f, 9.8f), 1L, 3)
        assertTrue(accumulator.accept(floatArrayOf(Float.NaN, 0f, 9f), 200_000_001L, 3) is SensorUpdate.Invalid)
        val next = accumulator.accept(floatArrayOf(0f, 0f, 9.7f), 300_000_001L, 3) as SensorUpdate.Published
        assertEquals(1, next.reading.samples.size)
        assertFalse(next.reading.values.any { !it.isFinite() })
        assertTrue(accumulator.accept(floatArrayOf(1f), 400_000_001L, 3) is SensorUpdate.Invalid)
        assertTrue(accumulator.accept(floatArrayOf(0f, 0f, 9f), 0L, 3) is SensorUpdate.Invalid)
    }

    @Test
    fun resetStartsANewMeasurementSession() {
        val accumulator = SensorReadingAccumulator(SensorKind.LIGHT)
        accumulator.accept(floatArrayOf(10f), 1L, 3)
        accumulator.reset()
        val result = accumulator.accept(floatArrayOf(20f), 2L, 3) as SensorUpdate.Published
        assertEquals(1, result.reading.samples.size)
        assertEquals(20f, result.reading.values.single(), 0f)
    }

    @Test
    fun largePositiveHardwareTimestampsDoNotOverflowTheThrottle() {
        val accumulator = SensorReadingAccumulator(SensorKind.PRESSURE)
        accumulator.accept(floatArrayOf(10f), 1L, 3)
        val latest = accumulator.accept(floatArrayOf(20f), Long.MAX_VALUE, 3) as SensorUpdate.Published
        assertEquals(Long.MAX_VALUE, latest.reading.timestampNanos)
        assertEquals(2, latest.reading.samples.size)
        assertTrue(accumulator.accept(floatArrayOf(30f), Long.MAX_VALUE - 1L, 3) is SensorUpdate.Ignored)
    }

    @Test
    fun onChangeLightPublishesTheFinalChangeEvenWithinTheThrottleInterval() {
        val accumulator = SensorReadingAccumulator(SensorKind.LIGHT)
        accumulator.accept(floatArrayOf(100f), 1_000_000_000L, 3)
        val changed = accumulator.accept(floatArrayOf(200f), 1_050_000_000L, 3) as SensorUpdate.Published
        assertEquals(200f, changed.reading.values.single(), 0f)
        assertEquals(1_050_000_000L, changed.reading.timestampNanos)
        assertEquals(listOf(100f, 200f), changed.reading.samples.map { it.value })
        assertTrue(accumulator.accept(floatArrayOf(200f), 1_100_000_000L, 3) is SensorUpdate.Ignored)
    }

    @Test
    fun onChangeLightStillPublishesAccuracyChangesAndBoundsItsHistory() {
        val accumulator = SensorReadingAccumulator(SensorKind.LIGHT)
        accumulator.accept(floatArrayOf(100f), 1L, 0)
        val improved = accumulator.accept(floatArrayOf(100f), 2L, 3) as SensorUpdate.Published
        assertEquals(3, improved.reading.accuracy)
        var latest = improved
        repeat(90) { index ->
            latest = accumulator.accept(floatArrayOf(101f + index), index + 3L, 3) as SensorUpdate.Published
        }
        assertEquals(60, latest.reading.samples.size)
    }
}

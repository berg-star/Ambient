package com.ambient.app.data.sensor

import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorReading
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class SensorReadingReducerTest {
    @Test
    fun unreliableAccuracyDoesNotTurnAValidMeasurementIntoAnAcquisitionFailure() {
        val accumulator = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
        val reading = SensorReadingReducer.onSample(
            SensorReading(), accumulator.accept(floatArrayOf(0f, .02f, .04f), 1L, 0), 0,
        )
        assertEquals(listOf(0f, .02f, .04f), reading.values)
        assertEquals(1L, reading.timestampNanos)
        assertEquals(0, reading.accuracy)
        assertEquals(0, reading.eventAccuracy)
        assertNull(reading.error)
    }

    @Test
    fun accuracyCanRecoverAndBecomeUnreliableAgainWithoutAStickyErrorOrGracePeriod() {
        val accumulator = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
        var reading = SensorReading()
        listOf(0, 3, 0, 1).forEachIndexed { index, accuracy ->
            val timestamp = index * 100_000_000L + 1L
            reading = SensorReadingReducer.onSample(
                reading, accumulator.accept(floatArrayOf(.1f, 0f, 0f), timestamp, accuracy), accuracy,
            )
            assertEquals(accuracy, reading.accuracy)
            assertEquals(timestamp, reading.timestampNanos)
            assertNull(reading.error)
        }
    }

    @Test
    fun callbackCannotInventTheFirstMeasurement() {
        val reading = SensorReadingReducer.onAccuracyChanged(SensorReading(isListening = true), 3)
        assertEquals(3, reading.accuracy)
        assertEquals(3, reading.callbackAccuracy)
        assertNull(reading.eventAccuracy)
        assertNull(reading.timestampNanos)
        assertEquals(emptyList<Float>(), reading.values)
    }

    @Test
    fun callbackCannotClearAnInvalidReadingErrorButAValidSampleCan() {
        val accumulator = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
        val invalid = SensorReadingReducer.onSample(
            SensorReading(), accumulator.accept(floatArrayOf(Float.NaN, 0f, 0f), 1L, 0), 0,
        )
        val changed = SensorReadingReducer.onAccuracyChanged(invalid, 3)
        assertEquals(invalid.error, changed.error)
        assertNull(changed.timestampNanos)
        val recovered = SensorReadingReducer.onSample(
            changed, accumulator.accept(floatArrayOf(.1f, 0f, 0f), 100_000_001L, 3), 3,
        )
        assertNull(recovered.error)
        assertEquals(100_000_001L, recovered.timestampNanos)
    }

    @Test
    fun eventAndCallbackReportsAreKeptSeparatelyEvenWhenTheyDisagree() {
        val accumulator = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
        val callback = SensorReadingReducer.onAccuracyChanged(SensorReading(), 3)
        val event = SensorReadingReducer.onSample(
            callback, accumulator.accept(floatArrayOf(.1f, 0f, 0f), 1L, 0), 0,
        )
        assertEquals(0, event.accuracy)
        assertEquals(0, event.eventAccuracy)
        assertEquals(3, event.callbackAccuracy)
        val nextCallback = SensorReadingReducer.onAccuracyChanged(event, 2)
        assertEquals(2, nextCallback.accuracy)
        assertEquals(0, nextCallback.eventAccuracy)
        assertEquals(2, nextCallback.callbackAccuracy)
        assertEquals(event.timestampNanos, nextCallback.timestampNanos)
        assertEquals(event.values, nextCallback.values)
    }

    @Test
    fun outOfOrderSamplesCannotOverwriteCurrentAccuracy() {
        val accumulator = SensorReadingAccumulator(SensorKind.LINEAR_ACCELERATION)
        val current = SensorReadingReducer.onSample(
            SensorReading(), accumulator.accept(floatArrayOf(.1f, 0f, 0f), 200_000_001L, 3), 3,
        )
        val ignored = accumulator.accept(floatArrayOf(.2f, 0f, 0f), 1L, 0)
        assertSame(current, SensorReadingReducer.onSample(current, ignored, 0))
    }
}

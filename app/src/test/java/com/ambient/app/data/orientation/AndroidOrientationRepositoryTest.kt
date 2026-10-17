package com.ambient.app.data.orientation

import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.domain.orientation.OrientationSource
import org.junit.Assert.*
import org.junit.Test

class AndroidOrientationRepositoryTest {
    @Test fun choosesMagneticRotationVectorBeforeRawFallback() {
        val fixture = fixture(OrientationSensor.entries.toSet())
        assertEquals(OrientationSource.ROTATION_VECTOR, fixture.repository.capability.source)
        fixture.repository.start()
        assertEquals(setOf(OrientationSensor.ROTATION_VECTOR), fixture.platform.current.registered)
    }

    @Test fun choosesRawPairThenAccelerometerOnlyAndNeverInventsCompass() {
        val pair = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        assertEquals(OrientationSource.ACCELEROMETER_MAGNETOMETER, pair.repository.capability.source)
        val level = fixture(setOf(OrientationSensor.ACCELEROMETER))
        level.repository.start()
        level.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        assertEquals(OrientationSource.ACCELEROMETER_ONLY, level.repository.capability.source)
        assertFalse(level.repository.capability.compassAvailable)
        assertNull(level.repository.state.value.angles!!.headingDegrees)
        assertEquals(0f, level.repository.state.value.angles!!.leftRightTiltDegrees, .001f)
    }

    @Test fun absentSensorsDoNotStartAThreadOrInventZeroAngles() {
        val fixture = fixture(emptySet())
        fixture.repository.start()
        assertEquals(0, fixture.platform.subscriptions.size)
        assertFalse(fixture.repository.state.value.isListening)
        assertNull(fixture.repository.state.value.angles)
        assertNotNull(fixture.repository.state.value.error)
    }

    @Test fun partialRegistrationFailureClosesWholeSubscriptionAndAllowsRetry() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        fixture.platform.failed = OrientationSensor.MAGNETOMETER
        fixture.repository.start()
        assertTrue(fixture.platform.current.closed)
        assertFalse(fixture.repository.state.value.isListening)
        assertNotNull(fixture.repository.state.value.error)
        fixture.platform.failed = null
        fixture.repository.start()
        assertEquals(2, fixture.platform.subscriptions.size)
        assertTrue(fixture.repository.state.value.isListening)
        assertNull(fixture.repository.state.value.error)
    }

    @Test fun platformOpenFailureIsAnExplicitAcquisitionError() {
        val fixture = fixture()
        fixture.platform.throwOnOpen = true
        fixture.repository.start()
        assertFalse(fixture.repository.state.value.isListening)
        assertNotNull(fixture.repository.state.value.error)
    }

    @Test fun repeatedStartIsIdempotentAndStopCancelsCallbacksAndRetainsPausedSample() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.repository.start()
        fixture.emit()
        val before = fixture.repository.state.value
        val subscription = fixture.platform.current
        fixture.repository.stop()
        assertEquals(1, fixture.platform.subscriptions.size)
        assertTrue(subscription.closed)
        assertEquals(0, subscription.actions.size)
        assertEquals(before.angles, fixture.repository.state.value.angles)
        assertEquals(before.timestampNanos, fixture.repository.state.value.timestampNanos)
        assertFalse(fixture.repository.state.value.isListening)
        fixture.repository.stop()
    }

    @Test fun restartClearsPausedValuesAndRetiredEventsCannotChangeNewSession() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        val old = fixture.platform.current
        fixture.repository.stop()
        fixture.advance(100)
        fixture.repository.start()
        assertNull(fixture.repository.state.value.angles)
        old.sample(OrientationSensor.ROTATION_VECTOR, rotationVector, fixture.now, 0)
        old.accuracy(OrientationSensor.ROTATION_VECTOR, 0)
        assertNull(fixture.repository.state.value.angles)
        assertNull(fixture.repository.state.value.accuracy)
        fixture.emit()
        assertNotNull(fixture.repository.state.value.angles)
    }

    @Test fun waitsFiveSecondsForFirstValidEventWithoutMandatoryWarmup() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.advance(4_999)
        assertNull(fixture.repository.state.value.error)
        fixture.advance(1)
        assertTrue(fixture.repository.state.value.error!!.contains("尚未收到"))
        fixture.emit()
        assertNotNull(fixture.repository.state.value.angles)
        assertNull(fixture.repository.state.value.error)
    }

    @Test fun missingUpdatesClearAnglesAfterTwoSecondsAndValidEventRecoversImmediately() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        val timestamp = fixture.repository.state.value.timestampNanos
        fixture.advance(2_000)
        assertNotNull(fixture.repository.state.value.angles)
        fixture.advance(250)
        assertNull(fixture.repository.state.value.angles)
        assertEquals(timestamp, fixture.repository.state.value.timestampNanos)
        assertTrue(fixture.repository.state.value.isStale)
        assertTrue(fixture.repository.state.value.isListening)
        fixture.emit()
        assertFalse(fixture.repository.state.value.isStale)
        assertNull(fixture.repository.state.value.error)
        assertNotNull(fixture.repository.state.value.angles)
    }

    @Test fun publicationUsesEventTimeAtTenHertz() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        val first = fixture.repository.state.value.timestampNanos
        fixture.advance(50)
        fixture.emit()
        assertEquals(first, fixture.repository.state.value.timestampNanos)
        fixture.advance(50)
        fixture.emit()
        assertEquals(fixture.now, fixture.repository.state.value.timestampNanos)
        assertEquals(3, fixture.platform.rotationCalls)
    }

    @Test fun delayedDeliveryCannotSubstituteWallClockForSamplingCadence() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        val first = fixture.now
        fixture.advance(150)
        fixture.emit(timestamp = first + 50_000_000L)
        assertEquals(first, fixture.repository.state.value.timestampNanos)
        fixture.advance(500)
        fixture.emit(timestamp = first + 100_000_000L)
        assertEquals(first + 100_000_000L, fixture.repository.state.value.timestampNanos)
    }

    @Test fun eventsBeforeSessionOutOfOrderDuplicateStaleAndFutureAreIgnored() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit(timestamp = fixture.now - 1)
        fixture.emit(timestamp = fixture.now + 1)
        assertNull(fixture.repository.state.value.angles)
        fixture.emit()
        val first = fixture.repository.state.value
        fixture.emit(accuracy = 0)
        fixture.emit(timestamp = fixture.now - 1, accuracy = 0)
        assertEquals(first, fixture.repository.state.value)
        fixture.advance(3_000)
        val stale = fixture.repository.state.value
        fixture.emit(timestamp = fixture.now - 2_500_000_000L)
        assertEquals(stale, fixture.repository.state.value)
    }

    @Test fun malformedNonFiniteAndInvalidQuaternionsNeverBecomeAReading() {
        val fixture = fixture()
        fixture.repository.start()
        listOf(floatArrayOf(0f), floatArrayOf(Float.NaN, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f, 0f)).forEach {
            fixture.emit(values = it)
        }
        assertNull(fixture.repository.state.value.angles)
        assertEquals(0, fixture.platform.rotationCalls)
        fixture.emit()
        assertNotNull(fixture.repository.state.value.angles)
    }

    @Test fun xyzOnlyRotationVectorIsSupportedWithUnknownHeadingAccuracy() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit(values = floatArrayOf(0f, 0f, 0f))
        assertNotNull(fixture.repository.state.value.angles)
        assertNull(fixture.repository.state.value.headingAccuracyDegrees)
        fixture.advance(100)
        val before = fixture.repository.state.value.timestampNanos
        fixture.emit(values = floatArrayOf(2f, 0f, 0f))
        assertEquals(before, fixture.repository.state.value.timestampNanos)
    }

    @Test fun accelerometerOnlyFreeFallCannotBecomeALevelReading() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER))
        fixture.repository.start()
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(.001f, .002f, .001f))
        assertNull(fixture.repository.state.value.angles)
        fixture.advance(100)
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        assertNotNull(fixture.repository.state.value.angles)
    }

    @Test fun eventArrayIsCopiedBeforeFallbackWaitsForOtherSensor() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        fixture.repository.start()
        val acceleration = floatArrayOf(0f, 0f, 9.8f)
        fixture.emit(OrientationSensor.ACCELEROMETER, acceleration)
        acceleration[2] = Float.NaN
        fixture.emit(OrientationSensor.MAGNETOMETER, floatArrayOf(0f, 30f, 0f))
        assertArrayEquals(floatArrayOf(0f, 0f, 9.8f), fixture.platform.lastAcceleration, 0f)
        assertNotNull(fixture.repository.state.value.angles)
    }

    @Test fun rawFallbackRequiresTwoRecentSourcesAndRecordsBothTimestamps() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        fixture.repository.start()
        val accelerationTime = fixture.now
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        assertNull(fixture.repository.state.value.angles)
        fixture.advance(1_001)
        fixture.emit(OrientationSensor.MAGNETOMETER, floatArrayOf(0f, 30f, 0f))
        assertNull(fixture.repository.state.value.angles)
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        val reading = fixture.repository.state.value
        assertEquals(fixture.now, reading.accelerationTimestampNanos)
        assertEquals(fixture.now, reading.magneticTimestampNanos)
        assertEquals(fixture.now, reading.timestampNanos)
        assertNotEquals(accelerationTime, reading.accelerationTimestampNanos)
    }

    @Test fun rawFallbackStalenessIncludesOlderContributorNotJustLatestTimestamp() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        fixture.repository.start()
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f))
        fixture.advance(1_000)
        fixture.emit(OrientationSensor.MAGNETOMETER, floatArrayOf(0f, 30f, 0f))
        assertNotNull(fixture.repository.state.value.angles)
        fixture.advance(1_250)
        assertTrue(fixture.repository.state.value.isStale)
        assertNull(fixture.repository.state.value.angles)
    }

    @Test fun failedMatrixDoesNotInventZeroOrientationOrRefreshOldReading() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.platform.matrix = null
        fixture.emit()
        assertNull(fixture.repository.state.value.angles)
        fixture.platform.matrix = identity.copyOf()
        fixture.advance(100)
        fixture.emit()
        val timestamp = fixture.repository.state.value.timestampNanos
        fixture.platform.matrix = null
        fixture.advance(100)
        fixture.emit()
        assertEquals(timestamp, fixture.repository.state.value.timestampNanos)
    }

    @Test fun missingAndUnavailableHeadingAccuracyRemainUnknownNotZero() {
        val fixture = fixture()
        fixture.repository.start()
        listOf(rotationVector, rotationVector + -1f, rotationVector + Float.NaN).forEachIndexed { i, values ->
            if (i > 0) fixture.advance(100)
            fixture.emit(values = values)
            assertNull(fixture.repository.state.value.headingAccuracyDegrees)
        }
        fixture.advance(100)
        fixture.emit(values = rotationVector + (Math.PI / 6).toFloat())
        assertEquals(30f, fixture.repository.state.value.headingAccuracyDegrees!!, .001f)
    }

    @Test fun unreliableAccuracyDoesNotMeanAcquisitionFailedAndCanRecover() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit(accuracy = 0)
        assertEquals(0, fixture.repository.state.value.accuracy)
        assertNotNull(fixture.repository.state.value.angles)
        assertNull(fixture.repository.state.value.error)
        fixture.platform.current.accuracy(OrientationSensor.ROTATION_VECTOR, 3)
        assertEquals(3, fixture.repository.state.value.accuracy)
        fixture.platform.current.accuracy(OrientationSensor.ROTATION_VECTOR, -1)
        assertNull(fixture.repository.state.value.accuracy)
    }

    @Test fun rawPairUsesWorseAccuracyAndUnknownContributorStaysUnknown() {
        val fixture = fixture(setOf(OrientationSensor.ACCELEROMETER, OrientationSensor.MAGNETOMETER))
        fixture.repository.start()
        fixture.emit(OrientationSensor.ACCELEROMETER, floatArrayOf(0f, 0f, 9.8f), accuracy = 3)
        fixture.emit(OrientationSensor.MAGNETOMETER, floatArrayOf(0f, 30f, 0f), accuracy = 1)
        assertEquals(1, fixture.repository.state.value.accuracy)
        fixture.platform.current.accuracy(OrientationSensor.MAGNETOMETER, null)
        assertNull(fixture.repository.state.value.accuracy)
        fixture.platform.current.accuracy(OrientationSensor.MAGNETOMETER, 0)
        assertEquals(0, fixture.repository.state.value.accuracy)
    }

    @Test fun accuracyCallbackCannotCreateFirstReadingOrRecoverStaleAngles() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.platform.current.accuracy(OrientationSensor.ROTATION_VECTOR, 3)
        assertNull(fixture.repository.state.value.angles)
        assertNull(fixture.repository.state.value.timestampNanos)
        fixture.emit()
        fixture.advance(2_250)
        fixture.platform.current.accuracy(OrientationSensor.ROTATION_VECTOR, 3)
        assertNull(fixture.repository.state.value.angles)
        assertTrue(fixture.repository.state.value.isStale)
    }

    @Test fun screenRotationReprojectsLatestMatrixWithoutInventingTimestamp() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        val timestamp = fixture.repository.state.value.timestampNanos
        fixture.repository.setDisplayRotation(DisplayRotation.R90)
        assertEquals(DisplayRotation.R90, fixture.repository.state.value.displayRotation)
        assertEquals(timestamp, fixture.repository.state.value.timestampNanos)
        assertEquals(90f, fixture.repository.state.value.angles!!.headingDegrees!!, .001f)
        fixture.repository.stop()
        fixture.repository.setDisplayRotation(DisplayRotation.R180)
        assertNull(fixture.repository.state.value.angles)
        assertFalse(fixture.repository.state.value.isListening)
    }

    @Test fun rotationOfStaleReadingCannotRecoverOldAngles() {
        val fixture = fixture()
        fixture.repository.start()
        fixture.emit()
        fixture.advance(2_250)
        fixture.repository.setDisplayRotation(DisplayRotation.R270)
        assertEquals(DisplayRotation.R270, fixture.repository.state.value.displayRotation)
        assertNull(fixture.repository.state.value.angles)
        assertTrue(fixture.repository.state.value.isStale)
    }

    private fun fixture(kinds: Set<OrientationSensor> = setOf(OrientationSensor.ROTATION_VECTOR)) = Fixture(kinds)

    private class Fixture(kinds: Set<OrientationSensor>) {
        var now = 10_000_000_000L
        val platform = FakePlatform(kinds) { now }
        val repository = AndroidOrientationRepository(platform) { now }
        fun advance(millis: Long) {
            val target = now + millis * 1_000_000
            while (true) {
                val pending = platform.subscriptions.flatMap { it.actions }.filter { it.time <= target }.minByOrNull { it.time } ?: break
                now = pending.time
                platform.subscriptions.forEach { it.actions.remove(pending) }
                pending.action()
            }
            now = target
        }
        fun emit(
            kind: OrientationSensor = OrientationSensor.ROTATION_VECTOR,
            values: FloatArray = rotationVector,
            timestamp: Long = now,
            accuracy: Int? = 3,
        ) = platform.current.sample(kind, values, timestamp, accuracy)
    }

    private class FakePlatform(kinds: Set<OrientationSensor>, val now: () -> Long) : OrientationPlatform {
        override val sensors = kinds.associateWith { it.name }
        val subscriptions = mutableListOf<FakeSubscription>()
        val current get() = subscriptions.last()
        var failed: OrientationSensor? = null
        var throwOnOpen = false
        var rotationCalls = 0
        var matrix: FloatArray? = identity.copyOf()
        var lastAcceleration: FloatArray? = null
        override fun open(
            onSample: (OrientationSensor, FloatArray, Long, Int?) -> Unit,
            onAccuracy: (OrientationSensor, Int?) -> Unit,
        ): OrientationSubscription {
            if (throwOnOpen) throw IllegalStateException("Test acquisition failure")
            return FakeSubscription(this, onSample, onAccuracy).also { subscriptions += it }
        }
        override fun rotationMatrix(rotationVector: FloatArray): FloatArray? { rotationCalls++; return matrix?.copyOf() }
        override fun rotationMatrix(acceleration: FloatArray, magnetic: FloatArray): FloatArray? {
            lastAcceleration = acceleration.copyOf()
            return matrix?.copyOf()
        }
    }
    private data class Action(val time: Long, val action: () -> Unit)
    private class FakeSubscription(
        val platform: FakePlatform,
        val sample: (OrientationSensor, FloatArray, Long, Int?) -> Unit,
        val accuracy: (OrientationSensor, Int?) -> Unit,
    ) : OrientationSubscription {
        val registered = mutableSetOf<OrientationSensor>()
        val actions = mutableListOf<Action>()
        var closed = false
        override fun register(kind: OrientationSensor): Boolean {
            if (platform.failed == kind) return false
            registered += kind
            return true
        }
        override fun postDelayed(delayMillis: Long, action: () -> Unit) {
            actions += Action(platform.now() + delayMillis * 1_000_000, action)
        }
        override fun close() { closed = true; actions.clear() }
    }

    private companion object {
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val rotationVector = floatArrayOf(0f, 0f, 0f, 1f)
    }
}

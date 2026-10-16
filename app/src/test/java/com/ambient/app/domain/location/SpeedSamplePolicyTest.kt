package com.ambient.app.domain.location

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedSamplePolicyTest {
    private val now = 100_000_000_000L
    private fun fix(
        hasSpeed: Boolean = true,
        speed: Float = 5f,
        accuracy: Float? = 0.3f,
        timestamp: Long = now,
        isMock: Boolean = false,
    ) = SpeedFix(hasSpeed, speed, accuracy, timestamp, isMock)

    @Test
    fun explicitProviderSpeedConvertsUnitsAndPreservesAccuracyAndProvenance() {
        val state = SpeedSamplePolicy.evaluate(fix(isMock = true), now)
        assertEquals(SpeedStatus.ACTIVE, state.status)
        val sample = requireNotNull(state.sample)
        assertEquals(5f, sample.metersPerSecond, 0f)
        assertEquals(18f, sample.kilometersPerHour, 0.0001f)
        assertEquals(0.3f, requireNotNull(sample.accuracyMetersPerSecond), 0f)
        assertEquals(now, sample.elapsedRealtimeNanos)
        assertTrue(sample.isMock)
    }

    @Test
    fun absentSpeedNeverBecomesZero() {
        val state = SpeedSamplePolicy.evaluate(fix(hasSpeed = false, speed = 0f), now)
        assertEquals(SpeedStatus.SPEED_UNAVAILABLE, state.status)
        assertNull(state.sample)
    }

    @Test
    fun explicitZeroFromProviderIsAValidObservation() {
        val sample = requireNotNull(SpeedSamplePolicy.evaluate(fix(speed = 0f), now).sample)
        assertEquals(0f, sample.metersPerSecond, 0f)
        assertFalse(sample.isMock)
    }

    @Test
    fun negativeNonFiniteAndOverflowingSpeedsAreNeverDisplayed() {
        listOf(-1f, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, Float.MAX_VALUE)
            .forEach { invalid ->
                val state = SpeedSamplePolicy.evaluate(fix(speed = invalid), now)
                assertEquals(SpeedStatus.ERROR, state.status)
                assertNull(state.sample)
            }
    }

    @Test
    fun invalidOptionalAccuracyIsHiddenWithoutDiscardingValidSpeed() {
        listOf(null, -1f, Float.NaN, Float.POSITIVE_INFINITY).forEach { accuracy ->
            val state = SpeedSamplePolicy.evaluate(fix(accuracy = accuracy), now)
            assertEquals(SpeedStatus.ACTIVE, state.status)
            assertNull(requireNotNull(state.sample).accuracyMetersPerSecond)
        }
    }

    @Test
    fun staleFixIsHiddenAtTheTenSecondBoundary() {
        assertEquals(
            SpeedStatus.ACTIVE,
            SpeedSamplePolicy.evaluate(fix(timestamp = now - SpeedSamplePolicy.MAX_AGE_NANOS), now).status,
        )
        val stale = SpeedSamplePolicy.evaluate(
            fix(timestamp = now - SpeedSamplePolicy.MAX_AGE_NANOS - 1L), now,
        )
        assertEquals(SpeedStatus.STALE, stale.status)
        assertNull(stale.sample)
    }

    @Test
    fun missingNegativeOrFutureTimestampsAreRejected() {
        listOf(0L, -1L, now + 1L, Long.MAX_VALUE).forEach { timestamp ->
            val state = SpeedSamplePolicy.evaluate(fix(timestamp = timestamp), now)
            assertEquals(SpeedStatus.ERROR, state.status)
            assertNull(state.sample)
        }
    }

    @Test
    fun invalidCurrentClockDoesNotMakeAFixFresh() {
        val state = SpeedSamplePolicy.evaluate(fix(), -1L)
        assertEquals(SpeedStatus.ERROR, state.status)
        assertNull(state.sample)
    }
}

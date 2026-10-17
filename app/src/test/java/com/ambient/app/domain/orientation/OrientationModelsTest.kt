package com.ambient.app.domain.orientation

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationModelsTest {
    @Test fun rotationVectorSupportsCompassAndLevel() {
        val capability = OrientationCapability(OrientationSource.ROTATION_VECTOR)
        assertTrue(capability.available)
        assertTrue(capability.compassAvailable)
        assertTrue(capability.levelAvailable)
    }

    @Test fun accelerationAndMagneticFieldSupportCompassAndLevel() {
        val capability = OrientationCapability(OrientationSource.ACCELEROMETER_MAGNETOMETER)
        assertTrue(capability.available)
        assertTrue(capability.compassAvailable)
        assertTrue(capability.levelAvailable)
    }

    @Test fun accelerationOnlyCannotClaimCompassSupport() {
        val capability = OrientationCapability(OrientationSource.ACCELEROMETER_ONLY)
        assertTrue(capability.available)
        assertFalse(capability.compassAvailable)
        assertTrue(capability.levelAvailable)
    }

    @Test fun unavailableSourceSupportsNeitherInstrument() {
        val capability = OrientationCapability(OrientationSource.UNAVAILABLE)
        assertFalse(capability.available)
        assertFalse(capability.compassAvailable)
        assertFalse(capability.levelAvailable)
    }

    @Test fun unattachedRepositoryCannotGenerateReadings() {
        UnavailableOrientationRepository.start()
        UnavailableOrientationRepository.setDisplayRotation(DisplayRotation.R180)
        UnavailableOrientationRepository.stop()
        assertFalse(UnavailableOrientationRepository.capability.available)
        assertFalse(UnavailableOrientationRepository.state.value.isListening)
        assertNull(UnavailableOrientationRepository.state.value.angles)
        assertNull(UnavailableOrientationRepository.state.value.timestampNanos)
    }
}

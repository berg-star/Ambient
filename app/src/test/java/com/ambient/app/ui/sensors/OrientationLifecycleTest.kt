package com.ambient.app.ui.sensors

import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.domain.orientation.OrientationCapability
import com.ambient.app.domain.orientation.OrientationReading
import com.ambient.app.domain.orientation.OrientationRepository
import com.ambient.app.domain.orientation.OrientationSource
import com.ambient.app.domain.sensor.AccelerationMode
import com.ambient.app.domain.sensor.SensorFeed
import com.ambient.app.domain.sensor.SensorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationLifecycleTest {
    @Test fun openingAndLeavingThePageControlsBothRepositories() {
        val raw = RawRepository()
        val pose = PoseRepository()
        val model = SensorsViewModel(raw, pose)
        assertEquals(0, pose.starts)
        model.start()
        assertEquals(1, raw.starts)
        assertEquals(1, pose.starts)
        model.stop()
        assertEquals(1, raw.stops)
        assertEquals(1, pose.stops)
        assertTrue(model.monitoringEnabled.value)
    }

    @Test fun pausedPreferenceSurvivesPageChangesForBothRepositories() {
        val raw = RawRepository()
        val pose = PoseRepository()
        val model = SensorsViewModel(raw, pose)
        model.start()
        model.setMonitoringEnabled(false)
        model.stop()
        model.start()
        assertEquals(1, pose.starts)
        assertEquals(1, raw.starts)
        model.setMonitoringEnabled(true)
        assertEquals(2, pose.starts)
        assertEquals(2, raw.starts)
    }

    @Test fun enablingAnInactivePageDoesNotStartOrientationHardware() {
        val pose = PoseRepository()
        val model = SensorsViewModel(RawRepository(), pose)
        model.setMonitoringEnabled(false)
        model.setMonitoringEnabled(true)
        assertEquals(0, pose.starts)
    }

    @Test fun accelerationModeChangesDoNotRestartOrientationOrReplaceItsState() {
        val raw = RawRepository()
        val pose = PoseRepository()
        val model = SensorsViewModel(raw, pose)
        model.start()
        val marker = OrientationReading(timestampNanos = 1234L, isListening = true)
        pose.state.value = marker
        model.setAccelerationMode(AccelerationMode.RAW)
        model.setAccelerationMode(AccelerationMode.LINEAR)
        assertEquals(1, pose.starts)
        assertEquals(0, pose.stops)
        assertSame(marker, model.orientationReading.value)
        assertEquals(AccelerationMode.LINEAR, raw.mode)
    }

    @Test fun displayRotationWhilePausedIsForwardedWithoutStartingSampling() {
        val pose = PoseRepository()
        val model = SensorsViewModel(RawRepository(), pose)
        model.setMonitoringEnabled(false)
        DisplayRotation.entries.forEach { rotation ->
            model.setDisplayRotation(rotation)
            assertEquals(rotation, pose.rotation)
        }
        assertEquals(0, pose.starts)
    }

    @Test fun orientationStateAndCapabilityRemainIndependentOfRawSensorAvailability() {
        val pose = PoseRepository()
        val model = SensorsViewModel(RawRepository(), pose)
        assertTrue(model.feeds.isEmpty())
        assertSame(pose.state, model.orientationReading)
        assertSame(pose.capability, model.orientationCapability)
        model.start()
        assertEquals(1, pose.starts)
    }

    private class RawRepository : SensorRepository {
        override val feeds = emptyList<SensorFeed>()
        var starts = 0
        var stops = 0
        var mode: AccelerationMode? = null
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun setAccelerationMode(mode: AccelerationMode) { this.mode = mode }
    }

    private class PoseRepository : OrientationRepository {
        override val capability = OrientationCapability(OrientationSource.ROTATION_VECTOR)
        override val state = MutableStateFlow(OrientationReading())
        var starts = 0
        var stops = 0
        var rotation = DisplayRotation.R0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun setDisplayRotation(rotation: DisplayRotation) { this.rotation = rotation }
    }
}

package com.ambient.app.ui.sensors

import com.ambient.app.domain.sensor.SensorFeed
import com.ambient.app.domain.sensor.AccelerationMode
import com.ambient.app.domain.sensor.SensorCapability
import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorReading
import com.ambient.app.domain.sensor.SensorRepository
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SensorsViewModelTest {
    @Test
    fun enablingAnInactivePageDoesNotStartHardware() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.setMonitoringEnabled(false)
        viewModel.setMonitoringEnabled(true)
        assertEquals(0, repository.startCalls)
        viewModel.start()
        assertEquals(1, repository.startCalls)
    }

    @Test
    fun leavingPageStopsHardwareWithoutChangingUserPreference() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.start()
        viewModel.stop()
        assertTrue(viewModel.monitoringEnabled.value)
        assertEquals(1, repository.stopCalls)
        viewModel.start()
        assertEquals(2, repository.startCalls)
    }

    @Test
    fun disabledMonitoringRemainsDisabledAcrossPageResumes() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.start()
        viewModel.setMonitoringEnabled(false)
        viewModel.stop()
        viewModel.start()
        assertFalse(viewModel.monitoringEnabled.value)
        assertEquals(1, repository.startCalls)
        viewModel.setMonitoringEnabled(true)
        assertEquals(2, repository.startCalls)
    }

    @Test
    fun accelerationDefaultsToLinearWithoutPretendingRawIsGravityFree() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        assertEquals(AccelerationMode.LINEAR, viewModel.accelerationMode.value)
        assertEquals(AccelerationMode.LINEAR, repository.mode)
        val acceleration = viewModel.visibleFeeds(viewModel.accelerationMode.value).single { it.kind.isAcceleration }
        assertEquals(SensorKind.LINEAR_ACCELERATION, acceleration.kind)
        assertFalse(acceleration.capability.available)
        assertEquals(null, acceleration.state.value.timestampNanos)
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun selectingRawKeepsOneAccelerationCardAndWorksWhenLinearIsMissing() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.setAccelerationMode(AccelerationMode.RAW)
        val acceleration = viewModel.visibleFeeds(viewModel.accelerationMode.value).single { it.kind.isAcceleration }
        assertEquals(SensorKind.ACCELEROMETER, acceleration.kind)
        assertTrue(acceleration.capability.available)
        assertEquals(AccelerationMode.RAW, repository.mode)
        assertEquals(0, repository.startCalls)
    }

    @Test
    fun changingModeWhilePausedDoesNotStartListeningOrResetTheSwitch() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.start()
        viewModel.setMonitoringEnabled(false)
        viewModel.setAccelerationMode(AccelerationMode.RAW)
        assertFalse(viewModel.monitoringEnabled.value)
        assertEquals(1, repository.startCalls)
        viewModel.setMonitoringEnabled(true)
        assertEquals(AccelerationMode.RAW, repository.mode)
        assertEquals(2, repository.startCalls)
    }

    @Test
    fun modeChoiceSurvivesLeavingAndResumingThePage() {
        val repository = RecordingRepository()
        val viewModel = SensorsViewModel(repository)
        viewModel.setAccelerationMode(AccelerationMode.RAW)
        viewModel.start()
        viewModel.stop()
        viewModel.start()
        assertEquals(AccelerationMode.RAW, viewModel.accelerationMode.value)
        assertEquals(AccelerationMode.RAW, repository.mode)
        assertEquals(2, repository.startCalls)
    }

    private class RecordingRepository : SensorRepository {
        override val feeds = SensorKind.entries.map { kind ->
            SensorFeed(kind, SensorCapability(available = kind != SensorKind.LINEAR_ACCELERATION), MutableStateFlow(SensorReading()))
        }
        var mode: AccelerationMode? = null
        var startCalls = 0
        var stopCalls = 0
        override fun setAccelerationMode(mode: AccelerationMode) { this.mode = mode }
        override fun start() { startCalls++ }
        override fun stop() { stopCalls++ }
    }
}

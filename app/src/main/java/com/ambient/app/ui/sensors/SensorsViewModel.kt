package com.ambient.app.ui.sensors

import androidx.lifecycle.ViewModel
import com.ambient.app.domain.sensor.AccelerationMode
import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorRepository
import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.domain.orientation.OrientationRepository
import com.ambient.app.domain.orientation.UnavailableOrientationRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SensorsViewModel(
    private val repository: SensorRepository,
    private val orientationRepository: OrientationRepository = UnavailableOrientationRepository,
) : ViewModel() {
    val feeds = repository.feeds
    val orientationCapability = orientationRepository.capability
    val orientationReading = orientationRepository.state
    private val mutableMonitoringEnabled = MutableStateFlow(true)
    val monitoringEnabled = mutableMonitoringEnabled.asStateFlow()
    private val mutableAccelerationMode = MutableStateFlow(AccelerationMode.LINEAR)
    val accelerationMode = mutableAccelerationMode.asStateFlow()
    private var pageIsActive = false

    init {
        repository.setAccelerationMode(mutableAccelerationMode.value)
    }

    /** A missing linear feed stays explicitly unavailable; raw data never impersonates it. */
    fun visibleFeeds(mode: AccelerationMode) = feeds.mapNotNull { feed ->
        when {
            feed.kind == SensorKind.LINEAR_ACCELERATION -> null
            feed.kind == SensorKind.ACCELEROMETER -> feeds.firstOrNull { it.kind == mode.kind }
            else -> feed
        }
    }

    fun setAccelerationMode(mode: AccelerationMode) {
        if (mutableAccelerationMode.value == mode) return
        repository.setAccelerationMode(mode)
        mutableAccelerationMode.value = mode
        if (pageIsActive && mutableMonitoringEnabled.value) repository.start()
    }

    fun setMonitoringEnabled(enabled: Boolean) {
        if (mutableMonitoringEnabled.value == enabled) return
        mutableMonitoringEnabled.value = enabled
        if (enabled && pageIsActive) startRepositories() else stopRepositories()
    }

    /** Called only while the sensor page is visible and the app is in the foreground. */
    fun start() {
        pageIsActive = true
        if (mutableMonitoringEnabled.value) startRepositories()
    }

    /** Pausing the page does not change the user's monitoring switch. */
    fun stop() {
        pageIsActive = false
        stopRepositories()
    }

    fun setDisplayRotation(rotation: DisplayRotation) = orientationRepository.setDisplayRotation(rotation)

    private fun startRepositories() { repository.start(); orientationRepository.start() }
    private fun stopRepositories() { repository.stop(); orientationRepository.stop() }

    override fun onCleared() {
        stopRepositories()
        super.onCleared()
    }
}

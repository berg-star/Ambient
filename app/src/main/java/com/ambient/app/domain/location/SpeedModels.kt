package com.ambient.app.domain.location

import kotlinx.coroutines.flow.StateFlow

enum class SpeedStatus {
    DISABLED,
    PERMISSION_REQUIRED,
    PRECISE_PERMISSION_REQUIRED,
    GPS_UNAVAILABLE,
    GPS_DISABLED,
    WAITING_FOR_FIX,
    SPEED_UNAVAILABLE,
    ACTIVE,
    STALE,
    ERROR,
}

/** Only an explicit speed from the system location provider is represented here. */
data class SpeedSample(
    val metersPerSecond: Float,
    val accuracyMetersPerSecond: Float?,
    val elapsedRealtimeNanos: Long,
    val isMock: Boolean,
) {
    val kilometersPerHour: Float get() = metersPerSecond * 3.6f
}

data class SpeedState(
    val status: SpeedStatus = SpeedStatus.DISABLED,
    val sample: SpeedSample? = null,
    val isListening: Boolean = false,
    val message: String? = null,
)

interface SpeedRepository {
    val state: StateFlow<SpeedState>
    fun start()
    fun stop()
    fun refreshAccess()
}

package com.ambient.app.domain.orientation

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Display rendering rotation (opposite physical rotation), matching Android Surface constants. */
enum class DisplayRotation { R0, R90, R180, R270 }

enum class OrientationSource {
    ROTATION_VECTOR,
    ACCELEROMETER_MAGNETOMETER,
    ACCELEROMETER_ONLY,
    UNAVAILABLE,
}

data class OrientationCapability(
    val source: OrientationSource,
    val rotationVectorName: String? = null,
    val accelerometerName: String? = null,
    val magneticFieldName: String? = null,
) {
    val available: Boolean get() = source != OrientationSource.UNAVAILABLE
    val levelAvailable: Boolean get() = available
    val compassAvailable: Boolean get() = source == OrientationSource.ROTATION_VECTOR ||
        source == OrientationSource.ACCELEROMETER_MAGNETOMETER
}

/**
 * Calculated angles relative to the current screen axes, rather than raw sensor measurements.
 * Heading is the screen's top edge clockwise from magnetic north. A nearly vertical top edge
 * has no useful horizontal direction, so heading is absent in that pose.
 * Positive tilts mean that the screen's right edge / top edge is raised respectively.
 * Both tilts can approach zero when the screen faces down; callers must also inspect [faceUp].
 */
data class OrientationAngles(
    val headingDegrees: Float?,
    val leftRightTiltDegrees: Float,
    val frontBackTiltDegrees: Float,
    val faceUp: Boolean,
)

data class OrientationReading(
    val angles: OrientationAngles? = null,
    val timestampNanos: Long? = null,
    val accuracy: Int? = null,
    val headingAccuracyDegrees: Float? = null,
    val isListening: Boolean = false,
    val isStale: Boolean = false,
    val error: String? = null,
    val accelerationTimestampNanos: Long? = null,
    val magneticTimestampNanos: Long? = null,
    val displayRotation: DisplayRotation = DisplayRotation.R0,
)

interface OrientationRepository {
    val capability: OrientationCapability
    val state: StateFlow<OrientationReading>
    fun start()
    fun stop()
    fun setDisplayRotation(rotation: DisplayRotation)
}

/** Default for consumers that do not have a device orientation repository attached. */
object UnavailableOrientationRepository : OrientationRepository {
    override val capability = OrientationCapability(OrientationSource.UNAVAILABLE)
    override val state = MutableStateFlow(OrientationReading()).asStateFlow()
    override fun start() = Unit
    override fun stop() = Unit
    override fun setDisplayRotation(rotation: DisplayRotation) = Unit
}

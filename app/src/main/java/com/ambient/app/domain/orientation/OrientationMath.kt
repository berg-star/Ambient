package com.ambient.app.domain.orientation

import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.hypot
import kotlin.math.sqrt

/** Pure geometry for Android's row-major, device-to-world 3 × 3 rotation matrix. */
object OrientationMath {
    // Within about 5.7 degrees of vertical, tiny horizontal errors dominate heading.
    private const val MIN_HEADING_PROJECTION = 0.1
    private const val MATRIX_TOLERANCE = 0.02
    private const val RAD_TO_DEG = 180.0 / Math.PI

    /**
     * World axes are east, magnetic north and up, matching SensorManager.getRotationMatrix.
     * Invalid, reflected or substantially non-orthogonal matrices produce no reading.
     * This does not establish sensor accuracy: a mathematically valid matrix may still be
     * affected by magnetic interference or device fusion error.
     */
    fun fromRotationMatrix(matrix: FloatArray, rotation: DisplayRotation): OrientationAngles? {
        if (matrix.size != 9 || matrix.any { !it.isFinite() }) return null
        val x = Vector(matrix[0].toDouble(), matrix[3].toDouble(), matrix[6].toDouble())
        val y = Vector(matrix[1].toDouble(), matrix[4].toDouble(), matrix[7].toDouble())
        val z = Vector(matrix[2].toDouble(), matrix[5].toDouble(), matrix[8].toDouble())
        if (listOf(x, y, z).any { abs(it.lengthSquared - 1.0) > MATRIX_TOLERANCE }) return null
        if (abs(x.dot(y)) > MATRIX_TOLERANCE || abs(y.dot(z)) > MATRIX_TOLERANCE ||
            abs(z.dot(x)) > MATRIX_TOLERANCE
        ) return null
        if (abs(x.cross(y).dot(z) - 1.0) > MATRIX_TOLERANCE * 2) return null

        val (right, top) = when (rotation) {
            DisplayRotation.R0 -> x to y
            // Display rotation describes rendered graphics, opposite to physical rotation.
            // Android's R90 mechanical-compass remap is AXIS_Y / AXIS_MINUS_X:
            // its new screen basis expressed in device axes is right=-Y, top=+X.
            DisplayRotation.R90 -> -y to x
            DisplayRotation.R180 -> -x to -y
            DisplayRotation.R270 -> y to -x
        }
        val topUnit = top.normalized() ?: return null
        val heading = if (hypot(topUnit.x, topUnit.y) < MIN_HEADING_PROJECTION) {
            null
        } else {
            val degrees = atan2(topUnit.x, topUnit.y) * RAD_TO_DEG
            val normalized = ((degrees % 360.0) + 360.0) % 360.0
            // Float rounding at the boundary must not produce 360 degrees.
            normalized.toFloat().let { if (it >= 360f) 0f else it }
        }
        return OrientationAngles(
            headingDegrees = heading,
            leftRightTiltDegrees = angleOfUpComponent(right),
            frontBackTiltDegrees = angleOfUpComponent(top),
            faceUp = z.z > 0.0,
        )
    }

    /**
     * Up expressed in device axes, for example a filtered accelerometer vector at rest.
     * Acceleration during movement can bias this estimate. No magnetic heading is inferred.
     * Vector magnitude does not calibrate or certify the estimate and is discarded here.
     */
    fun fromUpVector(up: FloatArray, rotation: DisplayRotation): OrientationAngles? {
        if (up.size != 3 || up.any { !it.isFinite() }) return null
        val unit = Vector(up[0].toDouble(), up[1].toDouble(), up[2].toDouble()).normalized() ?: return null
        val (rightUp, topUp) = when (rotation) {
            DisplayRotation.R0 -> unit.x to unit.y
            DisplayRotation.R90 -> -unit.y to unit.x
            DisplayRotation.R180 -> -unit.x to -unit.y
            DisplayRotation.R270 -> unit.y to -unit.x
        }
        return OrientationAngles(
            headingDegrees = null,
            leftRightTiltDegrees = degreesOfUpComponent(rightUp),
            frontBackTiltDegrees = degreesOfUpComponent(topUp),
            faceUp = unit.z > 0.0,
        )
    }

    private fun angleOfUpComponent(vector: Vector): Float =
        degreesOfUpComponent(vector.z / sqrt(vector.lengthSquared))

    private fun degreesOfUpComponent(component: Double): Float =
        (asin(component.coerceIn(-1.0, 1.0)) * RAD_TO_DEG).toFloat()

    private data class Vector(val x: Double, val y: Double, val z: Double) {
        val lengthSquared: Double get() = x * x + y * y + z * z
        fun normalized(): Vector? {
            val length = sqrt(lengthSquared)
            if (!length.isFinite() || length < 1e-6) return null
            return Vector(x / length, y / length, z / length)
        }
        fun dot(other: Vector) = x * other.x + y * other.y + z * other.z
        fun cross(other: Vector) = Vector(
            y * other.z - z * other.y,
            z * other.x - x * other.z,
            x * other.y - y * other.x,
        )
        operator fun unaryMinus() = Vector(-x, -y, -z)
    }
}

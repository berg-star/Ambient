package com.ambient.app.data.orientation

import androidx.test.core.app.ApplicationProvider
import android.hardware.SensorManager
import com.ambient.app.domain.orientation.DisplayRotation
import com.ambient.app.domain.orientation.OrientationMath
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sqrt

/** Synthetic vectors validate Android's actual conversion API, not a physical-device calibration. */
class AndroidOrientationMatrixTest {
    private val platform = AndroidOrientationPlatform(ApplicationProvider.getApplicationContext())

    @Test fun rotationVectorIdentityPointsToMagneticNorth() {
        val matrix = platform.rotationMatrix(floatArrayOf(0f, 0f, 0f, 1f))!!
        val angles = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R0)!!
        assertEquals(0f, angles.headingDegrees!!, .001f)
        assertEquals(0f, angles.leftRightTiltDegrees, .001f)
        assertEquals(0f, angles.frontBackTiltDegrees, .001f)
        assertTrue(angles.faceUp)
    }

    @Test fun rotationVectorClockwiseQuarterTurnPointsEast() {
        val half = sqrt(.5).toFloat()
        val matrix = platform.rotationMatrix(floatArrayOf(0f, 0f, -half, half))!!
        val angles = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R0)!!
        assertEquals(90f, angles.headingDegrees!!, .001f)
    }

    @Test fun rawAccelerationAndMagneticFieldMatchMagneticNorthAndScreenRemapping() {
        val matrix = platform.rotationMatrix(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(0f, 30f, -20f))!!
        val portrait = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R0)!!
        val landscape = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R90)!!
        assertEquals(0f, portrait.headingDegrees!!, .001f)
        assertEquals(90f, landscape.headingDegrees!!, .001f)
        val remapped = FloatArray(9)
        assertTrue(SensorManager.remapCoordinateSystem(matrix, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X, remapped))
        val androidRemapped = OrientationMath.fromRotationMatrix(remapped, DisplayRotation.R0)!!
        assertEquals(androidRemapped.headingDegrees!!, landscape.headingDegrees!!, .001f)
        assertEquals(androidRemapped.leftRightTiltDegrees, landscape.leftRightTiltDegrees, .001f)
        assertEquals(androidRemapped.frontBackTiltDegrees, landscape.frontBackTiltDegrees, .001f)
    }

    @Test fun failedFreeFallOrParallelVectorsReturnNoOrientation() {
        assertNull(platform.rotationMatrix(floatArrayOf(0f, 0f, 0f), floatArrayOf(0f, 30f, 0f)))
        assertNull(platform.rotationMatrix(floatArrayOf(0f, 0f, 9.8f), floatArrayOf(0f, 0f, 30f)))
    }

    @Test fun actualRotationVectorPreservesPositiveRightEdgeTilt() {
        val halfAngle = Math.toRadians(15.0)
        val matrix = platform.rotationMatrix(floatArrayOf(0f, -kotlin.math.sin(halfAngle).toFloat(), 0f, kotlin.math.cos(halfAngle).toFloat()))!!
        val portrait = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R0)!!
        assertEquals(30f, portrait.leftRightTiltDegrees, .001f)
        assertEquals(0f, portrait.frontBackTiltDegrees, .001f)
        val landscape = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R90)!!
        assertEquals(0f, landscape.leftRightTiltDegrees, .001f)
        assertEquals(30f, landscape.frontBackTiltDegrees, .001f)
    }

    @Test fun allDisplayRotationsMatchAndroidRemapAndOrientationApis() {
        val matrix = platform.rotationMatrix(floatArrayOf(0f, 0f, 0f, 1f))!!
        val rotations = listOf(
            Triple(DisplayRotation.R0, SensorManager.AXIS_X, SensorManager.AXIS_Y),
            Triple(DisplayRotation.R90, SensorManager.AXIS_Y, SensorManager.AXIS_MINUS_X),
            Triple(DisplayRotation.R180, SensorManager.AXIS_MINUS_X, SensorManager.AXIS_MINUS_Y),
            Triple(DisplayRotation.R270, SensorManager.AXIS_MINUS_Y, SensorManager.AXIS_X),
        )
        rotations.forEachIndexed { index, (rotation, x, y) ->
            val remapped = FloatArray(9)
            assertTrue(SensorManager.remapCoordinateSystem(matrix, x, y, remapped))
            val androidAngles = SensorManager.getOrientation(remapped, FloatArray(3))
            val androidHeading = ((Math.toDegrees(androidAngles[0].toDouble()) % 360.0) + 360.0) % 360.0
            val actual = OrientationMath.fromRotationMatrix(matrix, rotation)!!
            assertEquals(index * 90.0, androidHeading, .001)
            assertEquals(androidHeading.toFloat(), actual.headingDegrees!!, .001f)
        }
    }

    @Test fun androidAcceptsXyzOnlyRotationVectorAndDerivesQuaternionW() {
        val half = sqrt(.5).toFloat()
        val matrix = platform.rotationMatrix(floatArrayOf(0f, 0f, -half))!!
        val angles = OrientationMath.fromRotationMatrix(matrix, DisplayRotation.R0)!!
        assertEquals(90f, angles.headingDegrees!!, .001f)
    }
}

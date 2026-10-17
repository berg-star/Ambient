package com.ambient.app.domain.orientation

import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class OrientationMathTest {
    @Test fun flatPhoneUsesMagneticNorthAndClockwiseCardinalHeadings() {
        listOf(0f, 90f, 180f, 270f).forEach { heading ->
            val result = angles(headingMatrix(heading.toDouble()))
            assertAngle(heading, result.headingDegrees!!)
            assertAngle(0f, result.leftRightTiltDegrees)
            assertAngle(0f, result.frontBackTiltDegrees)
            assertTrue(result.faceUp)
        }
    }

    @Test fun screenTopIsRemappedForAllFourDisplayRotations() {
        val expected = listOf(0f, 90f, 180f, 270f)
        DisplayRotation.entries.forEachIndexed { index, rotation ->
            assertAngle(expected[index], angles(identity(), rotation).headingDegrees!!)
        }
    }

    @Test fun displayRotationAndPhysicalHeadingAreCombined() {
        val expected = listOf(35f, 125f, 215f, 305f)
        DisplayRotation.entries.forEachIndexed { index, rotation ->
            assertAngle(expected[index], angles(headingMatrix(35.0), rotation).headingDegrees!!)
        }
    }

    @Test fun physicalCounterClockwiseQuarterTurnAndDisplayR90KeepScreenTopNorth() {
        // Display.getRotation documents this physical turn as ROTATION_90.
        // Device +X now points north and +Y west; landscape screen top is device +X.
        val turnedCounterClockwise = floatArrayOf(0f, -1f, 0f, 1f, 0f, 0f, 0f, 0f, 1f)
        assertAngle(0f, angles(turnedCounterClockwise, DisplayRotation.R90).headingDegrees!!)
    }

    @Test fun physicalClockwiseQuarterTurnAndDisplayR270KeepScreenTopNorth() {
        // Device +X points south and +Y east; reverse-landscape top is device -X.
        val turnedClockwise = floatArrayOf(0f, 1f, 0f, -1f, 0f, 0f, 0f, 0f, 1f)
        assertAngle(0f, angles(turnedClockwise, DisplayRotation.R270).headingDegrees!!)
    }

    @Test fun headingWrapsAtNorthWithoutReturning360() {
        listOf(-0.00000001, 0.00000001, 359.99999999, -359.99999999, 720.0).forEach { input ->
            val heading = angles(headingMatrix(input)).headingDegrees!!
            assertTrue("$input -> $heading", heading >= 0f && heading < 360f)
        }
        assertAngle(359.5f, angles(headingMatrix(-0.5)).headingDegrees!!)
        assertAngle(0.5f, angles(headingMatrix(360.5)).headingDegrees!!)
    }

    @Test fun raisingTopEdgeProducesPositiveFrontBackTilt() {
        val result = angles(rotationX(30.0))
        assertAngle(30f, result.frontBackTiltDegrees)
        assertAngle(0f, result.leftRightTiltDegrees)
        assertAngle(0f, result.headingDegrees!!)
        assertTrue(result.faceUp)
    }

    @Test fun loweringTopEdgeProducesNegativeFrontBackTilt() {
        assertAngle(-40f, angles(rotationX(-40.0)).frontBackTiltDegrees)
    }

    @Test fun raisingRightEdgeProducesPositiveLeftRightTilt() {
        val result = angles(rotationY(-25.0))
        assertAngle(25f, result.leftRightTiltDegrees)
        assertAngle(0f, result.frontBackTiltDegrees)
        assertTrue(result.faceUp)
    }

    @Test fun loweringRightEdgeProducesNegativeLeftRightTilt() {
        assertAngle(-25f, angles(rotationY(25.0)).leftRightTiltDegrees)
    }

    @Test fun displayRotationRemapsBothSignedTiltAxes() {
        val expected = listOf(0f to 30f, -30f to 0f, 0f to -30f, 30f to 0f)
        DisplayRotation.entries.forEachIndexed { index, rotation ->
            val result = angles(rotationX(30.0), rotation)
            assertAngle(expected[index].first, result.leftRightTiltDegrees)
            assertAngle(expected[index].second, result.frontBackTiltDegrees)
        }
    }

    @Test fun faceDownFlatPhoneIsNeverReportedAsFaceUpLevel() {
        val result = angles(rotationX(180.0))
        assertFalse(result.faceUp)
        assertAngle(0f, result.leftRightTiltDegrees)
        assertAngle(0f, result.frontBackTiltDegrees)
        assertAngle(180f, result.headingDegrees!!)
    }

    @Test fun tiltingPastVerticalKeepsFaceDownDistinctFromTiltAngles() {
        val result = angles(rotationX(150.0))
        assertFalse(result.faceUp)
        assertAngle(30f, result.frontBackTiltDegrees)
        assertAngle(180f, result.headingDegrees!!)
    }

    @Test fun upwardVerticalScreenTopHasNoHeading() {
        val result = angles(rotationX(90.0))
        assertNull(result.headingDegrees)
        assertAngle(90f, result.frontBackTiltDegrees)
    }

    @Test fun downwardVerticalScreenTopHasNoHeading() {
        val result = angles(rotationX(-90.0))
        assertNull(result.headingDegrees)
        assertAngle(-90f, result.frontBackTiltDegrees)
    }

    @Test fun nearVerticalHeadingIsSuppressedBeforeProjectionCollapses() {
        assertNull(angles(rotationX(85.0)).headingDegrees)
        assertNull(angles(rotationX(-85.0)).headingDegrees)
        assertNotNull(angles(rotationX(84.0)).headingDegrees)
    }

    @Test fun verticalDeviceUsesCurrentScreenTopToDetermineHeadingAvailability() {
        val vertical = rotationX(90.0)
        assertNull(angles(vertical, DisplayRotation.R0).headingDegrees)
        assertAngle(90f, angles(vertical, DisplayRotation.R90).headingDegrees!!)
        assertNull(angles(vertical, DisplayRotation.R180).headingDegrees)
        assertAngle(270f, angles(vertical, DisplayRotation.R270).headingDegrees!!)
    }

    @Test fun verticalRightEdgeDoesNotInvalidateHorizontalTopHeading() {
        val result = angles(rotationY(-90.0))
        assertAngle(90f, result.leftRightTiltDegrees)
        assertAngle(0f, result.headingDegrees!!)
    }

    @Test fun wrongMatrixSizesAreRejected() {
        listOf(0, 3, 8, 10, 16).forEach { size ->
            assertNull(OrientationMath.fromRotationMatrix(FloatArray(size), DisplayRotation.R0))
        }
    }

    @Test fun nonFiniteMatrixEntriesAreRejectedInEveryPosition() {
        listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY).forEach { invalid ->
            repeat(9) { position ->
                assertNull(OrientationMath.fromRotationMatrix(identity().also { it[position] = invalid }, DisplayRotation.R0))
            }
        }
    }

    @Test fun zeroScaledShearedAndReflectedMatricesAreRejected() {
        val badMatrices = listOf(
            FloatArray(9),
            identity().map { it * 2 }.toFloatArray(),
            identity().also { it[1] = 0.25f },
            identity().also { it[8] = -1f },
        )
        badMatrices.forEach { assertNull(OrientationMath.fromRotationMatrix(it, DisplayRotation.R0)) }
    }

    @Test fun smallRoundingErrorsAreNormalizedAndRemainFinite() {
        val rounded = floatArrayOf(0.707f, 0.707f, 0f, -0.707f, 0.707f, 0f, 0f, 0f, 1.001f)
        val result = angles(rounded)
        assertAngle(45f, result.headingDegrees!!)
        assertAngle(0f, result.frontBackTiltDegrees)
        assertAngle(0f, result.leftRightTiltDegrees)
    }

    @Test fun headingFollowsSmallPhysicalYawAcrossNorth() {
        val headings = listOf(358.0, 359.0, 0.0, 1.0, 2.0)
        headings.forEach { assertAngle(it.toFloat(), angles(headingMatrix(it)).headingDegrees!!) }
    }

    @Test fun combinationsOfPhysicalRotationsKeepOutputFiniteAndInRange() {
        for (yaw in -180..180 step 45) for (pitch in -180..180 step 30) for (roll in -180..180 step 45) {
            val matrix = multiply(multiply(headingMatrix(yaw.toDouble()), rotationX(pitch.toDouble())), rotationY(roll.toDouble()))
            DisplayRotation.entries.forEach { rotation ->
                val result = angles(matrix, rotation)
                assertTrue(result.leftRightTiltDegrees.isFinite() && result.leftRightTiltDegrees in -90f..90f)
                assertTrue(result.frontBackTiltDegrees.isFinite() && result.frontBackTiltDegrees in -90f..90f)
                result.headingDegrees?.let { assertTrue(it.isFinite() && it >= 0f && it < 360f) }
            }
        }
    }

    @Test fun accelerationOnlyFlatEstimateHasNoInventedHeading() {
        val result = OrientationMath.fromUpVector(floatArrayOf(0f, 0f, 9.81f), DisplayRotation.R0)!!
        assertNull(result.headingDegrees)
        assertTrue(result.faceUp)
        assertAngle(0f, result.leftRightTiltDegrees)
        assertAngle(0f, result.frontBackTiltDegrees)
    }

    @Test fun accelerationOnlyFaceDownEstimateRemainsFaceDown() {
        val result = OrientationMath.fromUpVector(floatArrayOf(0f, 0f, -9.81f), DisplayRotation.R0)!!
        assertFalse(result.faceUp)
        assertNull(result.headingDegrees)
    }

    @Test fun upVectorAndRotationMatrixAgreeOnTiltForEveryDisplayRotation() {
        val matrix = multiply(rotationX(35.0), rotationY(-24.0))
        val up = floatArrayOf(matrix[6] * 9.81f, matrix[7] * 9.81f, matrix[8] * 9.81f)
        DisplayRotation.entries.forEach { rotation ->
            val actual = OrientationMath.fromUpVector(up, rotation)!!
            val expected = angles(matrix, rotation)
            assertNull(actual.headingDegrees)
            assertAngle(expected.leftRightTiltDegrees, actual.leftRightTiltDegrees)
            assertAngle(expected.frontBackTiltDegrees, actual.frontBackTiltDegrees)
            assertEquals(expected.faceUp, actual.faceUp)
        }
    }

    @Test fun upVectorMagnitudeIsDiscardedWithoutOverflowing() {
        val result = OrientationMath.fromUpVector(floatArrayOf(Float.MAX_VALUE, 0f, Float.MAX_VALUE), DisplayRotation.R0)!!
        assertAngle(45f, result.leftRightTiltDegrees)
        assertTrue(result.faceUp)
    }

    @Test fun invalidUpVectorsAreRejected() {
        listOf(
            floatArrayOf(), floatArrayOf(0f, 1f), floatArrayOf(0f, 0f, 1f, 0f),
            floatArrayOf(0f, 0f, 0f), floatArrayOf(Float.MIN_VALUE, 0f, 0f),
            floatArrayOf(Float.NaN, 0f, 1f), floatArrayOf(0f, Float.POSITIVE_INFINITY, 1f),
        ).forEach { assertNull(OrientationMath.fromUpVector(it, DisplayRotation.R0)) }
    }

    @Test fun matrixAndUpVectorInputsAreNotModified() {
        val matrix = rotationX(30.0)
        val expectedMatrix = matrix.copyOf()
        val up = floatArrayOf(0f, 4.905f, 8.4957f)
        val expectedUp = up.copyOf()
        angles(matrix, DisplayRotation.R270)
        OrientationMath.fromUpVector(up, DisplayRotation.R90)
        assertTrue(matrix.contentEquals(expectedMatrix))
        assertTrue(up.contentEquals(expectedUp))
    }

    private fun angles(matrix: FloatArray, rotation: DisplayRotation = DisplayRotation.R0): OrientationAngles =
        requireNotNull(OrientationMath.fromRotationMatrix(matrix, rotation))

    private fun assertAngle(expected: Float, actual: Float) = assertEquals(expected, actual, 0.002f)
    private fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    private fun headingMatrix(degrees: Double): FloatArray {
        val angle = Math.toRadians(degrees)
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return floatArrayOf(c, s, 0f, -s, c, 0f, 0f, 0f, 1f)
    }

    private fun rotationX(degrees: Double): FloatArray {
        val angle = Math.toRadians(degrees)
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return floatArrayOf(1f, 0f, 0f, 0f, c, -s, 0f, s, c)
    }

    private fun rotationY(degrees: Double): FloatArray {
        val angle = Math.toRadians(degrees)
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return floatArrayOf(c, 0f, s, 0f, 1f, 0f, -s, 0f, c)
    }

    private fun multiply(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { index ->
        val row = index / 3
        val col = index % 3
        (0..2).sumOf { k -> a[row * 3 + k].toDouble() * b[k * 3 + col] }.toFloat()
    }
}

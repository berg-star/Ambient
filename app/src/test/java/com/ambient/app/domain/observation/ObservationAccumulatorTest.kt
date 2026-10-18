package com.ambient.app.domain.observation

import org.junit.Assert.*
import org.junit.Test

class ObservationAccumulatorTest {
    private val start=1_000_000_000L
    private fun light() = ObservationAccumulator(ObservationKind.LIGHT,start,30)
    @Test fun allEventsContributeEvenWithinOneGraphBucket() {
        val a=light()
        listOf(10f,100f,30f).forEachIndexed { i,v -> a.accept(start+i+1,floatArrayOf(v),3,start+100) }
        val r=a.snapshot(start+200)
        assertEquals(3,r.count); assertEquals(1,r.bins.size)
        assertEquals(10.0,r.minimum!!,0.0); assertEquals(100.0,r.maximum!!,0.0)
        assertEquals(140.0/3,r.mean!!,1e-9); assertEquals(100.0,r.bins.single().max,0.0)
    }
    @Test fun chartHasAtMostOneHundredTwentyOneBucketsForSixtySeconds() {
        val a=ObservationAccumulator(ObservationKind.LIGHT,start,60)
        repeat(12_001) { i -> a.accept(start+i*5_000_000L,floatArrayOf(i.toFloat()),3,start+60_000_000_000L) }
        val r=a.snapshot(a.deadline,ObservationStatus.COMPLETED)
        assertEquals(12001,r.count); assertEquals(121,r.bins.size); assertEquals(12000.0,r.maximum!!,0.0)
    }
    @Test fun outOfOrderDuplicateBeforeStartFutureAndAfterDeadlineAreIgnored() {
        val a=light()
        a.accept(start-1,floatArrayOf(1f),3,start+5)
        a.accept(start+10,floatArrayOf(2f),3,start+5)
        a.accept(start+3,floatArrayOf(3f),3,start+5)
        a.accept(start+3,floatArrayOf(7f),3,start+5)
        a.accept(start+2,floatArrayOf(7f),3,start+5)
        a.accept(a.deadline+1,floatArrayOf(1f),3,a.deadline+1)
        assertEquals(1,a.snapshot(start+5).count); assertEquals(5,a.snapshot(start+5).rejected)
    }
    @Test fun zeroLightIsMeasuredButInvalidValuesNeverBecomeZero() {
        val a=light()
        a.accept(start,floatArrayOf(0f),3,start)
        listOf(-1f,Float.NaN,Float.POSITIVE_INFINITY).forEachIndexed { i,v -> a.accept(start+i+1,floatArrayOf(v),3,start+10) }
        a.accept(start+9,floatArrayOf(),3,start+10)
        assertEquals(1,a.snapshot(start+10).count); assertEquals(0.0,a.snapshot(start+10).mean!!,0.0)
        assertEquals(4,a.snapshot(start+10).rejected)
    }
    @Test fun noEventsProducesNoMeasurementsAndNoCurve() {
        val r=light().snapshot(start+30_000_000_000L,ObservationStatus.COMPLETED)
        assertNull(r.mean); assertNull(r.minimum); assertTrue(r.bins.isEmpty()); assertFalse(r.canCompare)
    }
    @Test fun repeatedUnchangedEventsAreIncludedAndGapsAreNotInvented() {
        val a=light()
        a.accept(start,floatArrayOf(10f),3,start)
        a.accept(start+20_000_000_000L,floatArrayOf(10f),3,start+20_000_000_000L)
        val r=a.snapshot(a.deadline)
        assertEquals(2,r.count); assertEquals(2,r.bins.size); assertEquals(10.0,r.mean!!,0.0)
    }
    @Test fun averageIsEventArithmeticMeanAndNotTimeWeighted() {
        val a=light()
        a.accept(start,floatArrayOf(100f),3,start)
        a.accept(start+29_000_000_000L,floatArrayOf(300f),3,start+29_000_000_000L)
        assertEquals(200.0,a.snapshot(a.deadline).mean!!,0.0)
    }
    @Test fun magneticNormIsCalculatedFromSignedThreeAxes() {
        val a=ObservationAccumulator(ObservationKind.MAGNETIC,start,30)
        a.accept(start,floatArrayOf(-3f,4f,0f),0,start)
        val r=a.snapshot(start+1)
        assertEquals(5.0,r.mean!!,0.0); assertEquals(listOf(-3f,4f,0f),r.values); assertEquals(1,r.unreliable)
        a.accept(start+2,floatArrayOf(1f,2f),3,start+2)
        assertEquals(1,a.snapshot(start+3).rejected)
    }
    @Test fun pressureMustBePositiveAndKeepsHectopascals() {
        val a=ObservationAccumulator(ObservationKind.PRESSURE,start,30)
        a.accept(start,floatArrayOf(0f),3,start)
        a.accept(start+1,floatArrayOf(1000f),3,start+1)
        assertEquals(1000.0,a.snapshot(start+2).mean!!,0.0); assertEquals("hPa",a.kind.unit)
    }
    @Test fun snapshotIsImmutableAndDurationIsClipped() {
        val a=light(); a.accept(start,floatArrayOf(10f),3,start)
        val old=a.snapshot(start+1)
        a.accept(start+2,floatArrayOf(20f),3,start+2)
        assertEquals(10.0,old.bins.single().value,0.0)
        assertEquals(30.0,a.snapshot(a.deadline+1).elapsedSeconds,0.0)
        assertFalse(a.snapshot(start+10,ObservationStatus.INTERRUPTED).canCompare)
        assertTrue(a.snapshot(a.deadline,ObservationStatus.COMPLETED).canCompare)
    }
    @Test fun unsupportedDurationIsRejected() {
        assertThrows(IllegalArgumentException::class.java) { ObservationAccumulator(ObservationKind.LIGHT,start,1) }
    }
}

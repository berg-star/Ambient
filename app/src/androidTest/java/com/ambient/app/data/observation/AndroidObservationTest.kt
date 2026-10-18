package com.ambient.app.data.observation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.domain.observation.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AndroidObservationTest {
    private fun repository() = AndroidObservationRepository(InstrumentationRegistry.getInstrumentation().targetContext)
    @Test fun openingDoesNotStartAndDuplicateStartKeepsSession() {
        val r=repository(); assumeTrue(r.capabilities[ObservationKind.LIGHT]?.available==true)
        try {
            assertEquals(ObservationStatus.IDLE,r.state.value.status)
            r.start(ObservationKind.LIGHT,30,"one"); val at=r.state.value.startedNanos
            r.start(ObservationKind.MAGNETIC,60,"two")
            assertEquals(at,r.state.value.startedNanos); assertEquals(ObservationKind.LIGHT,r.state.value.kind)
        } finally { r.stop("test cleanup") }
    }
    @Test fun stoppingLeavesPartialStableResultAndNextSessionResetsCounters(): Unit = runBlocking {
        val r=repository(); assumeTrue(r.capabilities[ObservationKind.LIGHT]?.available==true)
        try {
            r.start(ObservationKind.LIGHT,30,"one"); delay(150); r.stop("test interruption")
            val stopped=r.state.value; assertEquals(ObservationStatus.INTERRUPTED,stopped.status)
            delay(700); assertEquals(stopped,r.state.value)
            r.start(ObservationKind.LIGHT,60,"two")
            assertEquals("two",r.state.value.name); assertEquals(60,r.state.value.durationSeconds)
            assertEquals(ObservationStatus.RUNNING,r.state.value.status)
            assertTrue(r.state.value.startedNanos>stopped.startedNanos)
        } finally { r.stop("test cleanup") }
    }
    @Test fun thirtySecondDeadlineFinishesWithoutUiOrAnyNewEvent(): Unit = runBlocking {
        val r=repository(); assumeTrue(r.capabilities[ObservationKind.LIGHT]?.available==true)
        try {
            r.start(ObservationKind.LIGHT,30,"deadline")
            val end=withTimeout(35_000) { r.state.first { it.status==ObservationStatus.COMPLETED } }
            assertEquals(30.0,end.elapsedSeconds,0.0)
            delay(700); assertEquals(end,r.state.value)
        } finally { r.stop("test cleanup") }
    }
}

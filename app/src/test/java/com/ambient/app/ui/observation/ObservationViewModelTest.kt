package com.ambient.app.ui.observation

import com.ambient.app.domain.observation.*
import com.ambient.app.domain.sensor.SensorCapability
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test

class ObservationViewModelTest {
    private class Repository: ObservationRepository {
        override val capabilities=ObservationKind.entries.associateWith { SensorCapability(true) }
        override val state=MutableStateFlow(ObservationResult())
        var starts=0; var stops=0
        override fun start(kind: ObservationKind, seconds: Int, name: String) {
            if(state.value.status==ObservationStatus.RUNNING) return
            starts++; state.value=ObservationResult(kind,seconds,name,status=ObservationStatus.RUNNING)
        }
        override fun stop(reason: String) { if(state.value.status==ObservationStatus.RUNNING) {
            stops++; state.value=state.value.copy(status=ObservationStatus.INTERRUPTED,message=reason) } }
    }
    @Test fun openingDoesNotStartAndInvisiblePageCannotStart() {
        val r=Repository(); val m=ObservationViewModel(r)
        m.start(); assertEquals(0,r.starts); m.resume(); assertEquals(0,r.starts)
        m.start(); m.start(); assertEquals(1,r.starts)
    }
    @Test fun pauseStopsPartialAndResumeDoesNotAutomaticallyContinue() {
        val r=Repository(); val m=ObservationViewModel(r); m.resume(); m.start(); m.pause(); m.resume()
        assertEquals(1,r.starts); assertEquals(1,r.stops); assertEquals(ObservationStatus.INTERRUPTED,r.state.value.status)
    }
    @Test fun configurationCannotChangeDuringObservation() {
        val r=Repository(); val m=ObservationViewModel(r)
        m.configure(kind=ObservationKind.MAGNETIC,seconds=60,name="窗边")
        m.resume(); m.start(); m.configure(kind=ObservationKind.LIGHT,seconds=30)
        assertEquals(ObservationKind.MAGNETIC,m.config.value.kind); assertEquals(60,m.config.value.seconds)
    }
    @Test fun onlyCompleteNonemptyResultsCanBecomeReference() {
        val r=Repository(); val m=ObservationViewModel(r)
        m.keepReference(); assertNull(m.reference.value)
        r.state.value=ObservationResult(status=ObservationStatus.COMPLETED,count=1,mean=10.0)
        m.keepReference(); val saved=m.reference.value
        r.state.value=ObservationResult(status=ObservationStatus.RUNNING)
        m.keepReference(); assertEquals(saved,m.reference.value)
        m.clearReference(); assertNull(m.reference.value)
    }
}

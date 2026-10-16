package com.ambient.app.ui.location

import com.ambient.app.domain.location.SpeedRepository
import com.ambient.app.domain.location.SpeedState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeedViewModelTest {
    @Test
    fun speedIsOptInAndDoesNotStartOnInitialResume() {
        val repository = RecordingRepository()
        val viewModel = SpeedViewModel(repository)
        viewModel.resume()
        assertFalse(viewModel.enabled.value)
        assertEquals(0, repository.starts)
    }

    @Test
    fun enablingInactivePageWaitsForResumeAndPauseStopsLocation() {
        val repository = RecordingRepository()
        val viewModel = SpeedViewModel(repository)
        viewModel.setEnabled(true)
        assertEquals(0, repository.starts)
        viewModel.resume()
        assertEquals(1, repository.starts)
        viewModel.pause()
        assertEquals(1, repository.stops)
        assertTrue(viewModel.enabled.value)
        viewModel.resume()
        assertEquals(2, repository.starts)
    }

    @Test
    fun permissionRefreshOnlyTouchesLocationWhileOptedInAndResumed() {
        val repository = RecordingRepository()
        val viewModel = SpeedViewModel(repository)
        viewModel.refreshAccess()
        viewModel.resume()
        viewModel.refreshAccess()
        assertEquals(0, repository.refreshes)
        viewModel.setEnabled(true)
        viewModel.refreshAccess()
        assertEquals(1, repository.refreshes)
        viewModel.pause()
        viewModel.refreshAccess()
        assertEquals(1, repository.refreshes)
    }

    @Test
    fun disablingStopsAndDoesNotRestartOnResume() {
        val repository = RecordingRepository()
        val viewModel = SpeedViewModel(repository)
        viewModel.resume()
        viewModel.setEnabled(true)
        viewModel.setEnabled(false)
        viewModel.pause()
        viewModel.resume()
        assertFalse(viewModel.enabled.value)
        assertEquals(1, repository.starts)
        assertEquals(2, repository.stops)
    }

    private class RecordingRepository : SpeedRepository {
        override val state = MutableStateFlow(SpeedState())
        var starts = 0
        var stops = 0
        var refreshes = 0
        override fun start() { starts++ }
        override fun stop() { stops++ }
        override fun refreshAccess() { refreshes++ }
    }
}

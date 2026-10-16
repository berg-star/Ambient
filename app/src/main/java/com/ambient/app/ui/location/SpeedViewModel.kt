package com.ambient.app.ui.location

import androidx.lifecycle.ViewModel
import com.ambient.app.domain.location.SpeedRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

class SpeedViewModel(private val repository: SpeedRepository) : ViewModel() {
    val state = repository.state
    private val mutableEnabled = MutableStateFlow(false)
    val enabled = mutableEnabled.asStateFlow()
    private var pageIsResumed = false

    fun setEnabled(enabled: Boolean) {
        if (mutableEnabled.value == enabled) return
        mutableEnabled.value = enabled
        if (enabled) {
            if (pageIsResumed) repository.start()
        } else repository.stop()
    }

    fun resume() {
        pageIsResumed = true
        if (mutableEnabled.value) repository.start()
    }

    fun pause() {
        pageIsResumed = false
        repository.stop()
    }

    fun refreshAccess() {
        if (pageIsResumed && mutableEnabled.value) repository.refreshAccess()
    }

    override fun onCleared() {
        repository.stop()
        super.onCleared()
    }
}

package com.ambient.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.environment.EnvironmentRepository
import com.ambient.app.domain.environment.EnvironmentSnapshot
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class EnvironmentUiState(
    val snapshot: EnvironmentSnapshot? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
)

class EnvironmentViewModel(private val repository: EnvironmentRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(EnvironmentUiState())
    val state = mutableState.asStateFlow()

    init { refresh() }

    fun refresh() {
        if (mutableState.value.isRefreshing) return
        mutableState.value = mutableState.value.copy(isRefreshing = true, error = null)
        viewModelScope.launch {
            val result = repository.refresh()
            mutableState.value = EnvironmentUiState(result.snapshot, false, result.error)
        }
    }
}

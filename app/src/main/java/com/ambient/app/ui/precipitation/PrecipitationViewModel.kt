package com.ambient.app.ui.precipitation

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.precipitation.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class PrecipitationUiState(val result: PrecipitationResult = PrecipitationResult(null), val loading: Boolean = false)

class PrecipitationViewModel(private val repository: PrecipitationRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(PrecipitationUiState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var generation = 0
    fun load(force: Boolean = false) {
        if (work?.isActive == true) return
        mutableState.value = mutableState.value.copy(loading = true)
        val token = ++generation
        work = viewModelScope.launch {
            try {
                val result = repository.loadPrecipitation(force)
                if (token == generation) mutableState.value = PrecipitationUiState(result)
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutableState.value = PrecipitationUiState(mutableState.value.result.copy(
                    messages = listOf("降水预报读取失败，请重试。")))
            } finally { if (token == generation) mutableState.value = mutableState.value.copy(loading = false) }
        }
    }
    fun stop() { generation++; work?.cancel(); work = null; mutableState.value = mutableState.value.copy(loading = false) }
    fun reset() { stop(); mutableState.value = PrecipitationUiState() }
}

package com.ambient.app.ui.sky

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.sky.SkyRepository
import com.ambient.app.domain.sky.SkyResult
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

data class SkyUiState(val result: SkyResult = SkyResult(null), val loading: Boolean = false)

class SkyViewModel(private val repository: SkyRepository) : ViewModel() {
    private val mutableState = MutableStateFlow(SkyUiState())
    val state = mutableState.asStateFlow()
    private var work: Job? = null
    private var generation = 0

    fun load(force: Boolean = false) {
        if (work?.isActive == true) return
        mutableState.value = mutableState.value.copy(loading = true)
        val token = ++generation
        work = viewModelScope.launch {
            try {
                val result = repository.loadSky(force)
                if (token == generation) mutableState.value = SkyUiState(result)
            }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                if (token == generation) mutableState.value = SkyUiState(mutableState.value.result.copy(
                    messages = listOf("天文数据读取失败，请重试。")))
            }
            finally { if (token == generation) mutableState.value = mutableState.value.copy(loading = false) }
        }
    }
    fun stop() { generation++; work?.cancel(); work = null; mutableState.value = mutableState.value.copy(loading = false) }
    fun reset() { stop(); mutableState.value = SkyUiState() }
}

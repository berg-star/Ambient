package com.ambient.app.ui.observation

import androidx.lifecycle.ViewModel
import com.ambient.app.domain.observation.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

data class ObservationConfig(val kind: ObservationKind = ObservationKind.LIGHT, val seconds: Int = 30, val name: String = "")
class ObservationViewModel(private val repository: ObservationRepository) : ViewModel() {
    val state = repository.state
    val capabilities = repository.capabilities
    private val mutableConfig = MutableStateFlow(ObservationConfig())
    val config = mutableConfig.asStateFlow()
    private val mutableReference = MutableStateFlow<ObservationResult?>(null)
    val reference = mutableReference.asStateFlow()
    private var foreground = false
    fun configure(kind: ObservationKind = config.value.kind, seconds: Int = config.value.seconds, name: String = config.value.name) {
        if (state.value.status == ObservationStatus.RUNNING || seconds !in listOf(30,60)) return
        mutableConfig.value = ObservationConfig(kind, seconds, name.take(30))
    }
    fun resume() { foreground = true }
    fun pause() { foreground = false; repository.stop("离开页面或进入后台，记录已提前结束") }
    fun start() { if (foreground) config.value.let { repository.start(it.kind, it.seconds, it.name) } }
    fun stop() = repository.stop("手动结束，保留已记录的部分")
    fun keepReference() { state.value.takeIf { it.canCompare }?.let { mutableReference.value = it } }
    fun clearReference() { mutableReference.value = null }
    override fun onCleared() { repository.stop("观察会话已结束"); super.onCleared() }
}

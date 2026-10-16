package com.ambient.app.ui.overview

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.environment.EnvironmentRepository
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.environment.EnvironmentPreferences
import com.ambient.app.domain.environment.ManagedEnvironmentRepository
import com.ambient.app.data.location.AndroidAreaLocator
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant

data class EnvironmentUiState(
    val snapshot: EnvironmentSnapshot? = null,
    val isRefreshing: Boolean = false,
    val error: String? = null,
    val preferences: EnvironmentPreferences = EnvironmentPreferences(),
    val isCached: Boolean = false,
    val searchResults: List<EnvironmentArea> = emptyList(),
    val serviceMessage: String? = null,
    val isLocating: Boolean = false,
)

class EnvironmentViewModel(
    private val repository: EnvironmentRepository,
    private val locator: AndroidAreaLocator? = null,
) : ViewModel() {
    private val mutableState = MutableStateFlow(EnvironmentUiState())
    val state = mutableState.asStateFlow()

    private val managed get() = repository as? ManagedEnvironmentRepository
    private var work: Job? = null
    private var locationWork: Job? = null
    private var locationGeneration = 0

    init { runWork { load(force = false) } }

    fun refresh() {
        if (mutableState.value.isLocating) return
        runWork { load(force = true) }
    }

    private suspend fun load(force: Boolean) {
        val p = managed?.preferences() ?: EnvironmentPreferences()
        val cache = managed?.cachedSnapshot()
        mutableState.value = mutableState.value.copy(preferences = p, snapshot = cache, isCached = cache != null, error = null)
        if (p.enabled && p.area == null) {
            mutableState.value = mutableState.value.copy(serviceMessage = "请选择城市，或点击“使用当前位置”。")
            return
        }
        val age = cache?.let { Duration.between(it.updatedAt, Instant.now()).seconds }
        if (!force && age != null && age in 0 until 600) return
        val result = repository.refresh()
        mutableState.value = mutableState.value.copy(snapshot = result.snapshot,
            error = result.error, isCached = result.isCached)
    }

    private fun runWork(block: suspend () -> Unit) {
        if (work?.isActive == true) return
        mutableState.value = mutableState.value.copy(isRefreshing = true, serviceMessage = null)
        work = viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { mutableState.value = mutableState.value.copy(serviceMessage = e.userMessage) }
            catch (_: Exception) {
                mutableState.value = mutableState.value.copy(serviceMessage = "操作失败，请检查文件或网络后重试。")
            } finally { mutableState.value = mutableState.value.copy(isRefreshing = false) }
        }
    }

    fun importCredentials(reader: suspend () -> Pair<String, String>) = runWork {
        val (json, pem) = withContext(Dispatchers.IO) { reader() }
        managed?.importCredentials(json, pem)
        load(force = false)
        mutableState.value = mutableState.value.copy(serviceMessage = "凭据已加密保存。选择城市或使用当前位置即可获取数据。")
    }
    fun clearCredentials() = runWork {
        cancelLocation()
        managed?.clearCredentials(); load(force = false)
        mutableState.value = mutableState.value.copy(serviceMessage = "本机凭据、所选区域和天气缓存已清除。")
    }
    fun setEnabled(enabled: Boolean) = runWork {
        cancelLocation()
        managed?.setEnabled(enabled); load(force = false)
    }
    fun searchAreas(query: String) = runWork {
        mutableState.value = mutableState.value.copy(searchResults = emptyList())
        val results = managed?.searchAreas(query).orEmpty()
        mutableState.value = mutableState.value.copy(searchResults = results,
            serviceMessage = if (results.isEmpty()) "未找到城市，可尝试输入完整名称。" else "请选择一个区域。")
    }
    fun selectArea(area: EnvironmentArea) = runWork {
        cancelLocation()
        managed?.selectArea(area)
        mutableState.value = mutableState.value.copy(searchResults = emptyList())
        load(force = true)
    }
    fun dismissSearch() { mutableState.value = mutableState.value.copy(searchResults = emptyList(), serviceMessage = null) }
    fun locate() {
        if (locator == null || work?.isActive == true || locationWork?.isActive == true) return
        if (!mutableState.value.preferences.configured) { message("请先在设置中导入和风凭据。"); return }
        mutableState.value = mutableState.value.copy(isLocating = true, serviceMessage = "正在获取单次位置，最多等待 30 秒…")
        val generation = ++locationGeneration
        locationWork = viewModelScope.launch {
            try {
                val area = locator.locate()
                // Location is only sent to QWeather after this explicit user action.
                managed?.selectArea(area)
                mutableState.value = mutableState.value.copy(isLocating = false, serviceMessage = null)
                refresh()
            } catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { message(e.userMessage) }
            catch (_: Exception) { message("定位失败，可手动搜索城市。") }
            finally {
                if (generation == locationGeneration) mutableState.value = mutableState.value.copy(isLocating = false)
            }
        }
    }
    fun cancelLocation() {
        locationGeneration++
        if (locationWork?.isActive == true && mutableState.value.isLocating) {
            mutableState.value = mutableState.value.copy(isLocating = false, serviceMessage = "定位已停止，原区域保持不变。")
        }
        locationWork?.cancel(); locationWork = null
    }
    fun message(text: String) { mutableState.value = mutableState.value.copy(serviceMessage = text) }
}

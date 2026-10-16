package com.ambient.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.SnapshotPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

data class HistoryListState(val records: List<RecordSummary> = emptyList(), val loading: Boolean = true, val error: String? = null)
data class HistoryDetailState(val id: String? = null, val record: EnvironmentRecord? = null, val loading: Boolean = false, val error: String? = null)
data class HistoryActionState(val draft: SnapshotDraft? = null, val busy: Boolean = false, val message: String? = null, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: HistoryRepository) : ViewModel() {
    private val mutableActions = MutableStateFlow(HistoryActionState())
    val actions = mutableActions.asStateFlow()
    private val selectedId = MutableStateFlow<String?>(null)
    val selectedRecordId = selectedId.asStateFlow()
    private val reload = MutableStateFlow(0)
    val records = reload.flatMapLatest {
        repository.observeRecords().map { HistoryListState(records = it, loading = false) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(HistoryListState(loading = false, error = "历史记录读取失败，请重试。原数据不会被清空。"))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryListState())
    val detail = selectedId.flatMapLatest { id ->
        if (id == null) flowOf(HistoryDetailState()) else
            repository.observeRecord(id).map { HistoryDetailState(id, it, error = if (it == null) "这条记录已不存在。" else null) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(HistoryDetailState(id, error = "记录详情读取失败，请返回列表重试。"))
                }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryDetailState())

    fun prepareSave(snapshot: EnvironmentSnapshot?, wasCached: Boolean) {
        if (mutableActions.value.busy) return
        val reason = SnapshotPolicy.unavailableReason(snapshot)
        if (reason != null) { mutableActions.value = HistoryActionState(message = reason); return }
        // Freeze the displayed payload before the user adds a note; later weather refreshes cannot replace it.
        val frozen = snapshot!!.copy(metrics = snapshot.metrics.toList(), attributions = snapshot.attributions.toList(), notices = snapshot.notices.toList())
        mutableActions.value = HistoryActionState(draft = SnapshotDraft(UUID.randomUUID().toString(), frozen, wasCached))
    }
    fun cancelSave() { if (!mutableActions.value.busy) mutableActions.value = HistoryActionState() }
    fun save(note: String) {
        val draft = mutableActions.value.draft ?: return
        runAction {
            repository.save(draft, note)
            mutableActions.value = HistoryActionState(busy = true, message = "快照已保存，可在记录页查看。")
        }
    }
    fun open(id: String) {
        if (mutableActions.value.busy) return
        mutableActions.value = HistoryActionState()
        selectedId.value = id
    }
    fun closeDetail() { if (!mutableActions.value.busy) selectedId.value = null }
    fun clearFeedback() {
        if (!mutableActions.value.busy) mutableActions.value = mutableActions.value.copy(message = null, error = null)
    }
    fun retry() { reload.value++ }
    fun updateNote(id: String, note: String) = runAction {
        repository.updateNote(id, note)
        mutableActions.value = mutableActions.value.copy(message = "备注已更新。")
    }
    fun delete(id: String) = runAction {
        repository.delete(id)
        selectedId.value = null
        mutableActions.value = HistoryActionState(busy = true, message = "这条记录已删除。")
    }
    private fun runAction(block: suspend () -> Unit) {
        if (mutableActions.value.busy) return
        mutableActions.value = mutableActions.value.copy(busy = true, message = null, error = null)
        viewModelScope.launch {
            try { block() }
            catch (e: CancellationException) { throw e }
            catch (e: HistoryException) { mutableActions.value = mutableActions.value.copy(error = e.userMessage) }
            catch (_: Exception) { mutableActions.value = mutableActions.value.copy(error = "操作未完成，请检查可用存储空间后重试。") }
            finally { mutableActions.value = mutableActions.value.copy(busy = false) }
        }
    }
}

package com.ambient.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryRepository
import com.ambient.app.domain.history.HistoryFilter
import com.ambient.app.domain.history.HistoryTrend
import com.ambient.app.domain.history.TrendMetric
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotDraft
import com.ambient.app.domain.history.SnapshotPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID
import java.time.LocalDate
import java.time.ZoneId

data class HistoryListState(val records: List<RecordSummary> = emptyList(), val loading: Boolean = true, val error: String? = null, val appliedFilter: HistoryFilter? = null)
data class HistoryDetailState(val id: String? = null, val record: EnvironmentRecord? = null, val loading: Boolean = false, val error: String? = null)
data class HistoryActionState(val draft: SnapshotDraft? = null, val busy: Boolean = false, val message: String? = null, val error: String? = null)
data class HistoryAreasState(val areas: List<String> = emptyList(), val loading: Boolean = true, val error: String? = null)
data class HistoryTrendState(val trend: HistoryTrend? = null, val loading: Boolean = true, val error: String? = null, val areaRequired: Boolean = false, val appliedFilter: HistoryFilter? = null)
data class HistoryComparisonState(val records: List<EnvironmentRecord> = emptyList(), val loading: Boolean = false, val error: String? = null)

@OptIn(ExperimentalCoroutinesApi::class)
class HistoryViewModel(private val repository: HistoryRepository, val zoneId: ZoneId = ZoneId.systemDefault()) : ViewModel() {
    private val mutableActions = MutableStateFlow(HistoryActionState())
    val actions = mutableActions.asStateFlow()
    private val selectedId = MutableStateFlow<String?>(null)
    val selectedRecordId = selectedId.asStateFlow()
    private val reload = MutableStateFlow(0)
    private val mutableFilter = MutableStateFlow(HistoryFilter())
    val filter = mutableFilter.asStateFlow()
    private val mutableMetric = MutableStateFlow(TrendMetric.TEMPERATURE)
    val selectedMetric = mutableMetric.asStateFlow()
    private val mutableComparisonIds = MutableStateFlow<List<String>>(emptyList())
    val comparisonIds = mutableComparisonIds.asStateFlow()
    val comparison = combine(reload, mutableComparisonIds) { _, ids -> ids }.flatMapLatest { ids ->
        if (ids.size != 2) flowOf(HistoryComparisonState()) else
            combine(repository.observeRecord(ids[0]), repository.observeRecord(ids[1])) { first, second ->
                if (first == null || second == null) HistoryComparisonState(error = "选中的记录已不存在，请重新选择。")
                else HistoryComparisonState(records = listOf(first, second))
            }.onStart { emit(HistoryComparisonState(loading = true)) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(HistoryComparisonState(error = "快照对比读取失败，请重新选择。原数据不会被清空。"))
                }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryComparisonState())
    // Export always sees the complete collection, including when the current filter has no matches.
    val allRecords = reload.flatMapLatest {
        repository.observeRecords().map { HistoryListState(records = it, loading = false, appliedFilter = HistoryFilter()) }
            .onStart { emit(HistoryListState()) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(HistoryListState(loading = false, error = "历史记录读取失败，请重试。原数据不会被清空。"))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryListState())
    val records = combine(reload, mutableFilter) { _, filter -> filter }.flatMapLatest { filter ->
        repository.observeFilteredRecords(filter, zoneId).map { HistoryListState(records = it, loading = false, appliedFilter = filter) }
            .onStart { emit(HistoryListState(appliedFilter = filter)) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(HistoryListState(loading = false, error = "历史记录读取失败，请重试。原数据不会被清空。", appliedFilter = filter))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryListState())
    val areas = reload.flatMapLatest {
        repository.observeAreas().map { HistoryAreasState(areas = it, loading = false) }
            .onStart { emit(HistoryAreasState()) }
            .catch { e ->
                if (e is CancellationException) throw e
                emit(HistoryAreasState(loading = false, error = "区域列表读取失败，请重试。原数据不会被清空。"))
            }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryAreasState())
    private data class AnalysisQuery(val filter: HistoryFilter, val metric: TrendMetric, val areas: HistoryAreasState)
    val analysis = combine(reload, mutableFilter, mutableMetric, areas) { _, filter, metric, areas ->
        AnalysisQuery(filter, metric, areas)
    }.flatMapLatest { query ->
        val area = query.filter.areaLabel ?: query.areas.areas.singleOrNull()
        when {
            query.filter.areaLabel == null && query.areas.loading -> flowOf(HistoryTrendState(appliedFilter = query.filter))
            query.filter.areaLabel == null && query.areas.error != null -> flowOf(HistoryTrendState(loading = false, error = query.areas.error, appliedFilter = query.filter))
            area == null -> flowOf(HistoryTrendState(loading = false, areaRequired = query.areas.areas.size > 1, appliedFilter = query.filter))
            else -> repository.observeTrend(query.filter.copy(areaLabel = area), query.metric, zoneId)
                .map { HistoryTrendState(trend = it, loading = false, appliedFilter = query.filter) }
                .onStart { emit(HistoryTrendState(appliedFilter = query.filter)) }
                .catch { e ->
                    if (e is CancellationException) throw e
                    emit(HistoryTrendState(loading = false, error = "趋势读取失败，请重试。原数据不会被清空。", appliedFilter = query.filter))
                }
        }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), HistoryTrendState())
    val detail = combine(reload, selectedId) { _, id -> id }.flatMapLatest { id ->
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
    fun selectArea(areaLabel: String?) {
        mutableFilter.value = mutableFilter.value.copy(areaLabel = areaLabel)
        clearFeedback()
    }
    fun setDateRange(fromDate: LocalDate?, toDate: LocalDate?): Boolean {
        if (fromDate != null && toDate != null && fromDate > toDate) {
            mutableActions.value = mutableActions.value.copy(error = "开始日期不能晚于结束日期。")
            return false
        }
        mutableFilter.value = mutableFilter.value.copy(fromDate = fromDate, toDate = toDate)
        clearFeedback()
        return true
    }
    fun selectMetric(metric: TrendMetric) { mutableMetric.value = metric }
    fun clearFilter() { mutableFilter.value = HistoryFilter(); clearFeedback() }
    fun today(): LocalDate = LocalDate.now(zoneId)
    fun toggleComparison(id: String) {
        if (mutableActions.value.busy) return
        clearFeedback()
        val selected = mutableComparisonIds.value
        when {
            id in selected -> mutableComparisonIds.value = selected.filterNot { it == id }
            selected.size < 2 -> mutableComparisonIds.value = selected + id
            else -> mutableActions.value = mutableActions.value.copy(error = "最多对比两条记录，请先取消一条。")
        }
    }
    fun clearComparison() { mutableComparisonIds.value = emptyList(); clearFeedback() }
    fun updateNote(id: String, note: String) = runAction {
        repository.updateNote(id, note)
        mutableActions.value = mutableActions.value.copy(message = "备注已更新。")
    }
    fun delete(id: String) = runAction {
        repository.delete(id)
        mutableComparisonIds.value = mutableComparisonIds.value.filterNot { it == id }
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

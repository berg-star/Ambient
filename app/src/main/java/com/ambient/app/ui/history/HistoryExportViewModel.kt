package com.ambient.app.ui.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryExportDraft
import com.ambient.app.domain.history.HistoryExportService
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.OutputStream

enum class ExportStage { IDLE, PREPARING, CHOOSING, WRITING }
data class HistoryExportState(
    val stage: ExportStage = ExportStage.IDLE,
    val draft: HistoryExportDraft? = null,
    val pickerLaunched: Boolean = false,
    val message: String? = null,
    val error: String? = null,
) { val busy get() = stage != ExportStage.IDLE }

class HistoryExportViewModel(private val exporter: HistoryExportService) : ViewModel() {
    private val mutable = MutableStateFlow(HistoryExportState())
    val state = mutable.asStateFlow()

    fun prepare() {
        if (mutable.value.busy) return
        mutable.value = HistoryExportState(stage = ExportStage.PREPARING)
        viewModelScope.launch {
            try {
                val draft = exporter.prepare()
                mutable.value = HistoryExportState(stage = ExportStage.CHOOSING, draft = draft)
            } catch (e: CancellationException) { throw e }
            catch (e: HistoryException) { mutable.value = HistoryExportState(error = e.userMessage) }
            catch (_: Exception) { mutable.value = HistoryExportState(error = "导出准备失败，请检查可用存储空间后重试。历史记录未修改。") }
        }
    }

    /** Survives configuration changes: a replacement composable must not launch a second picker. */
    fun claimPicker(): HistoryExportDraft? {
        val state = mutable.value
        if (state.stage != ExportStage.CHOOSING || state.pickerLaunched) return null
        mutable.value = state.copy(pickerLaunched = true)
        return state.draft
    }

    fun pickerFailed() {
        val draft = mutable.value.draft ?: return
        mutable.value = mutable.value.copy(stage = ExportStage.WRITING)
        viewModelScope.launch {
            try { exporter.discard(draft) }
            catch (e: CancellationException) { throw e }
            catch (_: Exception) { /* A cache cleanup failure must not crash the application. */ }
            mutable.value = HistoryExportState(error = "系统保存入口未能打开，请稍后重新导出。")
        }
    }

    fun destination(openOutput: (() -> OutputStream)?) {
        val state = mutable.value
        val draft = state.draft
        if (draft == null || state.stage != ExportStage.CHOOSING) {
            if (openOutput != null && !state.busy) mutable.value = HistoryExportState(
                error = "导出已中断，请重新导出；刚创建的空文件可以删除。")
            return
        }
        mutable.value = state.copy(stage = ExportStage.WRITING)
        viewModelScope.launch {
            try {
                if (openOutput == null) {
                    exporter.discard(draft)
                    mutable.value = HistoryExportState(message = "已取消导出，历史记录保持不变。")
                } else {
                    exporter.write(draft, openOutput)
                    mutable.value = HistoryExportState(message = "已导出 ${draft.recordCount} 份快照 · ${draft.metricCount} 行数据。")
                }
            } catch (e: CancellationException) { throw e }
            catch (_: Exception) {
                mutable.value = HistoryExportState(error = "导出未完成，请重新导出。保存位置可能留下不完整文件，请检查后删除。历史记录未修改。")
            }
        }
    }
}

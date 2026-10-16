package com.ambient.app.ui.screens

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.RecordSummary
import com.ambient.app.domain.history.SnapshotPolicy
import com.ambient.app.ui.components.InstrumentSymbol
import com.ambient.app.ui.components.MetricSourceLabel
import com.ambient.app.ui.components.PageHeading
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.components.WindCompass
import com.ambient.app.ui.history.HistoryDetailState
import com.ambient.app.ui.history.HistoryViewModel
import com.ambient.app.ui.history.HistoryExportViewModel
import com.ambient.app.ui.history.HistoryListState
import com.ambient.app.ui.theme.AmbientPanel

@Composable
fun HistoryScreen(history: HistoryViewModel, export: HistoryExportViewModel, onOpenOverview: () -> Unit, modifier: Modifier = Modifier) {
    val list by history.records.collectAsStateWithLifecycle()
    val detail by history.detail.collectAsStateWithLifecycle()
    val selectedId by history.selectedRecordId.collectAsStateWithLifecycle()
    val action by history.actions.collectAsStateWithLifecycle()
    val all by history.allRecords.collectAsStateWithLifecycle()
    val filter by history.filter.collectAsStateWithLifecycle()
    val comparisonIds by history.comparisonIds.collectAsStateWithLifecycle()
    val visible = if (list.appliedFilter == filter) list else HistoryListState(appliedFilter = filter)
    val filterActive = filter.areaLabel != null || filter.fromDate != null || filter.toDate != null
    var showTrend by rememberSaveable { mutableStateOf(false) }
    BackHandler(selectedId != null) { history.closeDetail() }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(22.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item { PageHeading("JOURNAL / 环境记录", "留住这一刻", "按下保存，让每一次观察都有迹可循。") }
        if (all.records.isNotEmpty() || filterActive) item { HistoryFilterControl(history) }
        item { HistoryExportControl(export, !all.loading && all.error == null && all.records.isNotEmpty() && !action.busy) {
            TextButton(onClick = { showTrend = true }, enabled = !all.loading && all.error == null && all.records.isNotEmpty()) { Text("区域趋势 · 可选") }
        } }
        if (all.records.isNotEmpty() || comparisonIds.isNotEmpty()) item { HistoryComparisonControl(history) }
        action.message?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) } }
        if (selectedId == null) action.error?.let { error -> item { Text(error, color = MaterialTheme.colorScheme.error) } }
        when {
            visible.loading -> item {
                Row(Modifier.padding(vertical = 20.dp), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    Text("正在读取本机记录…", style = MaterialTheme.typography.bodyMedium)
                }
            }
            visible.error != null -> item {
                AmbientPanel {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(visible.error, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = history::retry) { Text("重试") }
                    }
                }
            }
            visible.records.isEmpty() && (filterActive || all.records.isNotEmpty()) -> item {
                AmbientPanel {
                    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text("没有符合筛选的记录", style = MaterialTheme.typography.titleMedium)
                        Text("按区域和原数据获取日期筛选，不会删除历史。", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = history::clearFilter) { Text("重置筛选") }
                    }
                }
            }
            visible.records.isEmpty() -> item {
                AmbientPanel {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 24.dp, vertical = 30.dp),
                        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(16.dp)) {
                        InstrumentSymbol("history", Modifier.size(42.dp))
                        Text("还没有环境记录", style = MaterialTheme.typography.titleLarge)
                        Text("在总览页保存一份真实区域数据。记录留在本机，断网也能回看。",
                            style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        TextButton(onClick = onOpenOverview) { Text("去总览保存") }
                    }
                }
            }
            else -> {
                item { Text("${visible.records.size} 条记录 · 按保存时间排列", style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
                items(visible.records, key = { it.id }) { record ->
                    RecordCard(record, comparisonIds.indexOf(record.id), { history.toggleComparison(record.id) }) { history.open(record.id) }
                }
            }
        }
        item { Text("仅保存在这部手机 · 不自动采集或上传", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(bottom = 12.dp)) }
    }
    selectedId?.let { id -> RecordDetailDialog(history, if (detail.id == id) detail else HistoryDetailState(id, loading = true)) }
    if (showTrend) HistoryTrendDialog(history) { showTrend = false }
}

@Composable
private fun RecordCard(record: RecordSummary, comparisonIndex: Int, onCompare: () -> Unit, onClick: () -> Unit) {
    AmbientPanel(Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(9.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text(historyTime(record.savedAt), style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.onSurfaceVariant)
                StatusBadge(if (record.wasCached) "缓存快照" else "区域快照")
            }
            Text(record.areaLabel, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Medium)
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Text((record.temperature ?: "—") + (record.temperatureUnit ?: ""), fontSize = 30.sp, fontWeight = FontWeight.Light)
                Text("AQI ${record.aqi ?: "—"}", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("数据获取 ${historyTime(record.fetchedAt)}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (record.note.isNotBlank()) Text(record.note, style = MaterialTheme.typography.bodySmall, maxLines = 2)
            TextButton(onClick = onCompare, modifier = Modifier.semantics {
                contentDescription = "${if (comparisonIndex < 0) "选择对比" else "取消对比"}：${record.areaLabel}，获取${historyTime(record.fetchedAt)}，备注：${record.note.ifBlank { "未添加" }}"
            }) { Text(if (comparisonIndex < 0) "选择对比" else "已选 ${if (comparisonIndex == 0) "A" else "B"} · 取消") }
        }
    }
}

@Composable
private fun RecordDetailDialog(history: HistoryViewModel, detail: HistoryDetailState) {
    val action by history.actions.collectAsStateWithLifecycle()
    var editing by rememberSaveable(detail.id) { mutableStateOf(false) }
    var deleting by rememberSaveable(detail.id) { mutableStateOf(false) }
    var note by rememberSaveable(detail.id) { mutableStateOf("") }
    LaunchedEffect(action.message, action.busy) { if (action.message == "备注已更新。" && !action.busy) editing = false }
    AlertDialog(onDismissRequest = history::closeDetail,
        title = { Text("环境快照") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                when {
                    detail.loading -> CircularProgressIndicator(Modifier.size(24.dp))
                    detail.error != null -> Text(detail.error, color = MaterialTheme.colorScheme.error)
                    detail.record != null -> {
                        RecordDetailContent(detail.record)
                        action.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                        if (!editing && !deleting) action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = history::closeDetail, enabled = !action.busy) { Text("关闭") } },
        dismissButton = {
            detail.record?.let { record ->
                Row {
                    TextButton(onClick = { history.clearFeedback(); note = record.note; editing = true }, enabled = !action.busy) { Text("备注") }
                    TextButton(onClick = { history.clearFeedback(); deleting = true }, enabled = !action.busy) { Text("删除", color = MaterialTheme.colorScheme.error) }
                }
            }
        })
    if (editing && detail.record != null) AlertDialog(onDismissRequest = { if (!action.busy) editing = false },
        title = { Text("编辑备注") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                OutlinedTextField(note, { note = it.take(SnapshotPolicy.MAX_NOTE_LENGTH) }, label = { Text("备注") }, minLines = 3, maxLines = 5,
                    enabled = !action.busy, supportingText = { Text("${note.length}/${SnapshotPolicy.MAX_NOTE_LENGTH}") })
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { history.updateNote(detail.record.id, note) }, enabled = !action.busy) { Text(if (action.busy) "保存中…" else "保存备注") } },
        dismissButton = { TextButton(onClick = { editing = false }, enabled = !action.busy) { Text("取消") } })
    if (deleting && detail.record != null) AlertDialog(onDismissRequest = { if (!action.busy) deleting = false },
        title = { Text("删除这条记录？") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("只删除当前这份快照，删除后无法恢复。其他记录及首页数据不受影响。")
                action.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            }
        },
        confirmButton = { TextButton(onClick = { history.delete(detail.record.id) }, enabled = !action.busy) { Text(if (action.busy) "删除中…" else "确认删除") } },
        dismissButton = { TextButton(onClick = { deleting = false }, enabled = !action.busy) { Text("取消") } })
}

@Composable
private fun RecordDetailContent(record: EnvironmentRecord) {
    val uriHandler = LocalUriHandler.current
    Text(record.snapshot.areaLabel, style = MaterialTheme.typography.titleMedium)
    StatusBadge(if (record.wasCached) "区域 API · 缓存快照" else "区域 API · 已保存")
    Text("保存时间：${historyTime(record.savedAt)}", style = MaterialTheme.typography.bodySmall)
    Text("数据获取：${historyTime(record.snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
    Text(record.snapshot.observedAt?.let { "观测时间：${historyTime(it)}" } ?: "服务未提供独立观测时间。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Text("备注：${record.note.ifBlank { "未添加" }}", style = MaterialTheme.typography.bodyMedium)
    HorizontalDivider()
    record.snapshot.metrics.forEach { metric ->
        Column(verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                Text(metric.label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                Text(metric.value + if (metric.unit.isBlank()) "" else " ${metric.unit}", style = MaterialTheme.typography.bodyMedium)
            }
            MetricSourceLabel(metric)
            Text(metric.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (metric.id == "windDirection") WindCompass(metric.windFromDegrees, metric.value, Modifier.size(72.dp).align(Alignment.CenterHorizontally))
        }
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
    }
    record.snapshot.notices.forEach { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    Text("数据来源：区域环境 API", style = MaterialTheme.typography.labelSmall)
    record.snapshot.attributions.forEach { link ->
        Text(link, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.clickable { if (link.startsWith("https://")) runCatching { uriHandler.openUri(link) } }.padding(vertical = 5.dp))
    }
}

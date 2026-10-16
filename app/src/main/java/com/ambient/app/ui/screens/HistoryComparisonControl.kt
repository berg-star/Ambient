package com.ambient.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.history.EnvironmentRecord
import com.ambient.app.domain.history.HistoryComparisonBuilder
import com.ambient.app.ui.components.MetricSourceLabel
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.history.HistoryViewModel
import com.ambient.app.ui.theme.AmbientPanel
import java.util.Locale

@Composable
fun HistoryComparisonControl(history: HistoryViewModel) {
    val ids by history.comparisonIds.collectAsStateWithLifecycle()
    val all by history.allRecords.collectAsStateWithLifecycle()
    val state by history.comparison.collectAsStateWithLifecycle()
    var show by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(ids) { if (ids.size != 2) show = false }
    if (ids.isEmpty()) {
        Text("选择两份快照，并排查看当时的区域与环境。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else AmbientPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
            Text("已选 ${ids.size}/2 份快照", style = MaterialTheme.typography.titleSmall)
            ids.forEachIndexed { index, id ->
                val record = all.records.firstOrNull { it.id == id }
                Text("${if (index == 0) "A" else "B"} · ${record?.areaLabel ?: "记录已不存在"}" +
                    (record?.let { " · ${historyTime(it.fetchedAt)}" } ?: ""), style = MaterialTheme.typography.labelSmall)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { show = true }, enabled = ids.size == 2) { Text("对比这两份快照") }
                TextButton(onClick = { show = false; history.clearComparison() }) { Text("清空选择") }
            }
        }
    }
    if (show && ids.size == 2) {
        val ready = state.records.map { it.id } == ids && !state.loading && state.error == null
        val comparison = if (ready) remember(state.records) { HistoryComparisonBuilder.build(state.records[0], state.records[1]) } else null
        AlertDialog(onDismissRequest = { show = false }, title = { Text("快照对比") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when {
                        state.error != null -> Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        comparison == null -> CircularProgressIndicator()
                        else -> {
                            ComparisonMetadata("A", comparison.left) { show = false; history.open(comparison.left.id) }
                            ComparisonMetadata("B", comparison.right) { show = false; history.open(comparison.right.id) }
                            comparison.notice?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
                            Text("差值表示 B − A；不说明变化原因。", style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            HorizontalDivider()
                            comparison.metrics.forEach { row ->
                                Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                                    val labelsDiffer = row.left != null && row.right != null && row.left.label != row.right.label
                                    Text(if (row.id == "aqi" && labelsDiffer) "空气质量（标准不同）" else row.label, style = MaterialTheme.typography.titleSmall)
                                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("A", style = MaterialTheme.typography.labelSmall)
                                            if (labelsDiffer) Text(row.left.label, style = MaterialTheme.typography.labelSmall)
                                            Text(row.left?.let { it.value + if (it.unit.isBlank()) "" else " ${it.unit}" } ?: "—",
                                                style = MaterialTheme.typography.bodyLarge)
                                            row.left?.let { MetricSourceLabel(it) }
                                        }
                                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                            Text("B", style = MaterialTheme.typography.labelSmall)
                                            if (labelsDiffer) Text(row.right.label, style = MaterialTheme.typography.labelSmall)
                                            Text(row.right?.let { it.value + if (it.unit.isBlank()) "" else " ${it.unit}" } ?: "—",
                                                style = MaterialTheme.typography.bodyLarge)
                                            row.right?.let { MetricSourceLabel(it) }
                                        }
                                    }
                                    row.delta?.let {
                                        val number = String.format(Locale.US, "%+.2f", if (it == 0.0) 0.0 else it)
                                        Text("计算差值 $number ${row.deltaUnit}", style = MaterialTheme.typography.bodyMedium,
                                            color = MaterialTheme.colorScheme.primary)
                                    }
                                    row.explanation?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    val leftDescription = row.left?.description
                                    val rightDescription = row.right?.description
                                    if (leftDescription == rightDescription) leftDescription?.let {
                                        Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                    } else {
                                        leftDescription?.let { Text("A：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                        rightDescription?.let { Text("B：$it", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
                                    }
                                }
                                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .5f))
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { show = false }) { Text("关闭对比") } },
            dismissButton = { if (state.error != null) TextButton(onClick = history::retry) { Text("重试") } })
    }
}

@Composable
private fun ComparisonMetadata(label: String, record: EnvironmentRecord, onOpen: () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text("$label · ${record.snapshot.areaLabel}", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
        StatusBadge(if (record.wasCached) "缓存快照" else "已保存快照")
        Text("获取：${historyTime(record.snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
        Text("保存：${historyTime(record.savedAt)}", style = MaterialTheme.typography.labelSmall)
        Text(record.snapshot.observedAt?.let { "观测：${historyTime(it)}" } ?: "服务未提供独立观测时间。",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        if (record.note.isNotBlank()) Text("备注：${record.note}", style = MaterialTheme.typography.bodySmall)
        TextButton(onClick = onOpen) { Text("查看快照 $label") }
    }
}

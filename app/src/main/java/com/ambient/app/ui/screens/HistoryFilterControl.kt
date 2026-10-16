package com.ambient.app.ui.screens

import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DateRangePicker
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberDateRangePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.history.HistoryFilter
import com.ambient.app.ui.history.HistoryViewModel
import com.ambient.app.ui.theme.AmbientPanel
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

@Composable
fun HistoryFilterControl(history: HistoryViewModel) {
    val filter by history.filter.collectAsStateWithLifecycle()
    val areas by history.areas.collectAsStateWithLifecycle()
    var choosingArea by rememberSaveable { mutableStateOf(false) }
    var choosingDatePreset by rememberSaveable { mutableStateOf(false) }
    var choosingDates by rememberSaveable { mutableStateOf(false) }
    val today = history.today()
    AmbientPanel(Modifier.fillMaxWidth()) {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("筛选记录", style = MaterialTheme.typography.titleMedium)
                if (filter != HistoryFilter()) TextButton(onClick = history::clearFilter) { Text("重置筛选") }
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { choosingArea = true }, modifier = Modifier.weight(1.5f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                    Text("区域：${filter.areaLabel ?: "全部区域"}", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                OutlinedButton(onClick = { choosingDatePreset = true }, modifier = Modifier.weight(1f),
                    contentPadding = PaddingValues(horizontal = 8.dp, vertical = 8.dp)) {
                    val label = when {
                        filter.fromDate == null && filter.toDate == null -> "全部日期"
                        filter.fromDate == today && filter.toDate == today -> "今天"
                        filter.fromDate == today.minusDays(6) && filter.toDate == today -> "近 7 天"
                        filter.fromDate == today.minusDays(29) && filter.toDate == today -> "近 30 天"
                        else -> "自选日期"
                    }
                    Text("日期：$label", maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            if (filter.fromDate != null || filter.toDate != null) {
                Text("${dateLabel(filter.fromDate, "不限开始")} 至 ${dateLabel(filter.toDate, "不限结束")}",
                    style = MaterialTheme.typography.bodySmall)
            }
            Text("按数据获取日期筛选 · 手机本地日期", style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (choosingArea) AlertDialog(
        onDismissRequest = { choosingArea = false },
        title = { Text("选择记录区域") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("列表可查看全部区域；趋势图每次展示一个区域。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                AreaChoice("全部区域", filter.areaLabel == null) { history.selectArea(null); choosingArea = false }
                when {
                    areas.loading -> Text("正在读取区域…")
                    areas.error != null -> {
                        Text(areas.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = history::retry) { Text("重试") }
                    }
                    areas.areas.isEmpty() -> Text("保存真实快照后会在这里列出区域。", style = MaterialTheme.typography.bodySmall)
                    else -> LazyColumn(Modifier.fillMaxWidth().heightIn(max = 360.dp)) {
                        items(areas.areas, key = { it }) { area ->
                            AreaChoice(area, filter.areaLabel == area) { history.selectArea(area); choosingArea = false }
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { choosingArea = false }) { Text("关闭") } },
    )
    if (choosingDatePreset) AlertDialog(
        onDismissRequest = { choosingDatePreset = false },
        title = { Text("选择获取日期") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    DatePresetChip("全部日期", filter, null, null, history) { choosingDatePreset = false }
                    DatePresetChip("今天", filter, today, today, history) { choosingDatePreset = false }
                    DatePresetChip("近 7 天", filter, today.minusDays(6), today, history) { choosingDatePreset = false }
                    DatePresetChip("近 30 天", filter, today.minusDays(29), today, history) { choosingDatePreset = false }
                }
                TextButton(onClick = { choosingDatePreset = false; choosingDates = true }) { Text("自选日期") }
                Text("按数据获取日期筛选，包含开始与结束当天。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = { choosingDatePreset = false }) { Text("关闭") } },
    )
    if (choosingDates) HistoryDateRangeDialog(filter, onDismiss = { choosingDates = false }) { from, to ->
        if (history.setDateRange(from, to)) choosingDates = false
    }
}

@Composable
private fun DatePresetChip(label: String, filter: HistoryFilter, from: LocalDate?, to: LocalDate?, history: HistoryViewModel, onSelected: () -> Unit) {
    FilterChip(selected = filter.fromDate == from && filter.toDate == to,
        onClick = { if (history.setDateRange(from, to)) onSelected() }, label = { Text(label) })
}

@Composable
private fun AreaChoice(label: String, selected: Boolean, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().selectable(selected = selected, role = Role.RadioButton, onClick = onClick).padding(vertical = 3.dp),
        verticalAlignment = Alignment.CenterVertically) {
        RadioButton(selected, onClick = null)
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.padding(start = 10.dp))
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryDateRangeDialog(filter: HistoryFilter, onDismiss: () -> Unit, onConfirm: (LocalDate, LocalDate) -> Unit) {
    // The Material picker encodes calendar dates as UTC midnight, independently of the filtering time zone.
    val state = rememberDateRangePickerState(
        initialSelectedStartDateMillis = filter.fromDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
        initialSelectedEndDateMillis = filter.toDate?.atStartOfDay(ZoneOffset.UTC)?.toInstant()?.toEpochMilli(),
    )
    Dialog(onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.surface) {
            Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text("取消") }
                    Text("选择日期范围", style = MaterialTheme.typography.titleMedium)
                    TextButton(enabled = state.selectedStartDateMillis != null && state.selectedEndDateMillis != null,
                        onClick = {
                            val start = state.selectedStartDateMillis
                            val end = state.selectedEndDateMillis
                            if (start != null && end != null) onConfirm(
                                Instant.ofEpochMilli(start).atZone(ZoneOffset.UTC).toLocalDate(),
                                Instant.ofEpochMilli(end).atZone(ZoneOffset.UTC).toLocalDate(),
                            )
                        }) { Text("应用") }
                }
                HorizontalDivider()
                DateRangePicker(state, modifier = Modifier.fillMaxWidth().weight(1f),
                    title = { Text("包含开始与结束当天", modifier = Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) })
            }
        }
    }
}

private fun dateLabel(date: LocalDate?, fallback: String): String = date?.format(DateTimeFormatter.ofPattern("yyyy.MM.dd")) ?: fallback

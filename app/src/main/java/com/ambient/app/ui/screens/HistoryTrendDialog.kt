package com.ambient.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.history.HistoryTrend
import com.ambient.app.domain.history.TrendMetric
import com.ambient.app.domain.history.TrendPoint
import com.ambient.app.ui.components.MetricSourceLabel
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.history.HistoryViewModel
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs

@Composable
fun HistoryTrendDialog(history: HistoryViewModel, onDismiss: () -> Unit) {
    val analysis by history.analysis.collectAsStateWithLifecycle()
    val metric by history.selectedMetric.collectAsStateWithLifecycle()
    val areas by history.areas.collectAsStateWithLifecycle()
    val filter by history.filter.collectAsStateWithLifecycle()
    val expectedArea = filter.areaLabel ?: areas.areas.singleOrNull()
    val currentTrend = analysis.trend?.takeIf {
        analysis.appliedFilter == filter && it.metric == metric && it.areaLabel == expectedArea
    }
    val changingQuery = analysis.appliedFilter != filter || (analysis.trend != null && currentTrend == null)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("区域趋势 · 可选") },
        text = {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("按原始数据获取时间查看快照；不同区域分别展示。", style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TrendMetric.entries.chunked(2).forEach { row ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        row.forEach { option ->
                            FilterChip(selected = option == metric, onClick = { history.selectMetric(option) },
                                label = { Text(if (option == TrendMetric.AQI) "AQI" else option.label) }, modifier = Modifier.weight(1f))
                        }
                    }
                }
                when {
                    analysis.loading || changingQuery -> Row(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalAlignment = Alignment.CenterVertically) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                        Text("正在读取趋势…")
                    }
                    analysis.error != null -> {
                        Text(analysis.error!!, color = MaterialTheme.colorScheme.error)
                        TextButton(onClick = history::retry) { Text("重试趋势") }
                    }
                    analysis.areaRequired -> {
                        Text("先选择一个区域，再查看趋势。", style = MaterialTheme.typography.bodyMedium)
                        areas.areas.forEach { area ->
                            TextButton(onClick = { history.selectArea(area) }, modifier = Modifier.fillMaxWidth()) { Text(area) }
                        }
                    }
                    currentTrend != null -> TrendContent(currentTrend, history.zoneId)
                    else -> Text("暂无可查看的区域快照。先在总览页保存真实数据。", style = MaterialTheme.typography.bodyMedium)
                }
                HorizontalDivider()
                Text("连线仅辅助比较，间隔内未持续采集；获取时间不等于观测时间。",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("关闭趋势") } },
    )
}

@Composable
private fun TrendContent(trend: HistoryTrend, zone: ZoneId) {
    Text(trend.areaLabel, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Medium)
    if (trend.points.isEmpty()) {
        Text("当前区域和日期内没有快照。", style = MaterialTheme.typography.bodyMedium)
        return
    }
    val points = trend.points
    var selectedIndex by rememberSaveable(trend.areaLabel, trend.metric.name) { mutableIntStateOf(points.lastIndex) }
    val selected = selectedIndex.coerceIn(points.indices)
    val range = remember(points) { HistoryTrendGeometry.yRange(points.mapNotNull { it.value }) }
    val validCount = points.count { it.value != null }
    Text("${points.size} 个获取时间点 · ${trend.metric.unit}", style = MaterialTheme.typography.labelMedium)
    if (points.size == 1) Text("只有一个获取时间点，暂时无法判断变化。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (points.size > 1 && validCount == 1) Text("只有一个有效读数，暂时无法判断变化。", style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (validCount == 0) {
        Text("该指标暂无可绘制数值；缺失数据不会填为 0。", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Column(Modifier.width(42.dp).height(176.dp), verticalArrangement = Arrangement.SpaceBetween) {
                Text(axisNumber(range.endInclusive), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(axisNumber(range.start), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            TrendCanvas(points, range, selected, { selectedIndex = it }, Modifier.weight(1f).height(176.dp))
        }
        val axisFormatter = remember(zone) { DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(zone) }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(axisFormatter.format(points.first().fetchedAt), style = MaterialTheme.typography.labelSmall)
            if (points.size > 1) Text(axisFormatter.format(points.last().fetchedAt), style = MaterialTheme.typography.labelSmall)
        }
        Text("横轴为数据获取时间 · 纵轴自动缩放 · 点击查看原始点", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    if (trend.duplicateCount > 0) Text("本窗口合并 ${trend.duplicateCount} 份重复获取时间的快照；重复保存缓存不算新观测。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (trend.hasOlder) Text("仅显示最近 120 个获取时间点。可缩小日期范围查看更早记录。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (validCount in 1 until points.size) Text("缺失或无效读数保留为空点，并中断连线。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    HorizontalDivider()
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
        TextButton(onClick = { selectedIndex = selected - 1 }, enabled = selected > 0) { Text("上一点") }
        Text("${selected + 1} / ${points.size}", style = MaterialTheme.typography.labelMedium)
        TextButton(onClick = { selectedIndex = selected + 1 }, enabled = selected < points.lastIndex) { Text("下一点") }
    }
    TrendPointContent(points[selected], zone)
}

@Composable
private fun TrendCanvas(points: List<TrendPoint>, range: ClosedFloatingPointRange<Double>, selected: Int,
    onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    val primary = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .55f)
    val selection = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = .45f)
    val first = points.first().fetchedAt
    val last = points.last().fetchedAt
    Canvas(modifier.semantics { contentDescription = "${points.size} 个原始获取时间点的趋势图，可使用下方上一点和下一点查看详情" }
        .pointerInput(points) {
            detectTapGestures { tap ->
                val padding = 8.dp.toPx()
                val width = (size.width - 2 * padding).coerceAtLeast(1f)
                val index = points.indices.minByOrNull {
                    abs(tap.x - (padding + HistoryTrendGeometry.xFraction(first, last, points[it].fetchedAt) * width))
                }
                index?.let(onSelect)
            }
        }) {
        val padding = 8.dp.toPx()
        val width = (size.width - 2 * padding).coerceAtLeast(1f)
        val height = (size.height - 2 * padding).coerceAtLeast(1f)
        fun x(index: Int) = padding + HistoryTrendGeometry.xFraction(first, last, points[index].fetchedAt) * width
        fun position(index: Int, value: Double) = Offset(x(index), padding + (1f - HistoryTrendGeometry.yFraction(value, range)) * height)
        for (fraction in listOf(0f, .5f, 1f)) {
            val y = padding + fraction * height
            drawLine(grid, Offset(padding, y), Offset(padding + width, y), strokeWidth = 1.dp.toPx())
        }
        drawLine(selection, Offset(x(selected), padding), Offset(x(selected), padding + height), strokeWidth = 1.dp.toPx())
        points.forEachIndexed { index, point ->
            val value = point.value ?: return@forEachIndexed
            if (index > 0) points[index - 1].value?.let { previous ->
                drawLine(primary, position(index - 1, previous), position(index, value), strokeWidth = 2.dp.toPx())
            }
            drawCircle(primary, radius = if (index == selected) 4.5.dp.toPx() else 2.5.dp.toPx(), center = position(index, value))
        }
    }
}

@Composable
private fun TrendPointContent(point: TrendPoint, zone: ZoneId) {
    val formatter = remember(zone) { DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss", Locale.CHINA).withZone(zone) }
    val metric = point.metric
    Text("原始记录：${metric?.value ?: "—"}${metric?.unit?.takeIf { it.isNotBlank() }?.let { " $it" }.orEmpty()}",
        style = MaterialTheme.typography.titleMedium)
    point.missingReason?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant) }
    StatusBadge(if (point.wasCached) "缓存快照" else "已保存快照")
    Text("数据获取：${formatter.format(point.fetchedAt)}", style = MaterialTheme.typography.bodySmall)
    Text("保存时间：${formatter.format(point.savedAt)}", style = MaterialTheme.typography.bodySmall)
    Text(point.observedAt?.let { "观测时间：${formatter.format(it)}" } ?: "服务未提供独立观测时间。",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    metric?.let {
        MetricSourceLabel(it)
        Text(it.description, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

private fun axisNumber(value: Double): String = String.format(Locale.CHINA, if (abs(value) >= 10000) "%.1e" else "%.1f", value)

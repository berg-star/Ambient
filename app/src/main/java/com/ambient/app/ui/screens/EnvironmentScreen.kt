package com.ambient.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.ui.overview.EnvironmentViewModel
import androidx.compose.ui.platform.LocalUriHandler
import com.ambient.app.ui.components.InstrumentSymbol
import com.ambient.app.ui.components.MetricSourceLabel
import com.ambient.app.ui.overview.EnvironmentUiState
import com.ambient.app.ui.theme.AmbientPanel
import com.ambient.app.ui.theme.LocalAmbientVisuals
import com.ambient.app.ui.theme.ThemeStyle
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EnvironmentScreen(
    state: EnvironmentUiState,
    onRefresh: () -> Unit,
    onOpenSensors: () -> Unit,
    modifier: Modifier = Modifier,
    overview: EnvironmentViewModel? = null,
    onSettings: () -> Unit = {},
) {
    val snapshot = state.snapshot
    val temperature = snapshot?.metrics?.firstOrNull { it.id == "temperature" }
    // The four useful overview tiles stay together; the rest remain available below.
    val priority = listOf("humidity", "pressure", "aqi", "altitude")
    val otherMetrics = snapshot?.metrics?.filterNot { it.id == "temperature" }
        ?.sortedBy { priority.indexOf(it.id).let { index -> if (index >= 0) index else priority.size } }.orEmpty()
    val visuals = LocalAmbientVisuals.current
    var selectedMetric by remember { mutableStateOf<EnvironmentMetric?>(null) }
    val demo = snapshot?.isDemo ?: !state.preferences.enabled
    val uriHandler = LocalUriHandler.current
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 22.dp, vertical = 4.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(snapshot?.areaLabel ?: state.preferences.area?.label ?: "区域数据尚未就绪", style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Medium)
                    Text(snapshot?.updatedAt?.let {
                        (if (demo) "演示加载 " else if (state.isCached) "缓存 · 获取于 " else "获取于 ") +
                            DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(ZoneId.systemDefault()).format(it)
                    } ?: "尚无加载记录", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                TextButton(onClick = onRefresh, enabled = !state.isRefreshing && !state.isLocating,
                    contentPadding = PaddingValues(horizontal = 8.dp)) {
                    if (state.isRefreshing) CircularProgressIndicator(Modifier.size(15.dp), strokeWidth = 2.dp)
                    else RefreshSymbol()
                    Spacer(Modifier.width(6.dp))
                    Text(if (state.isRefreshing) "加载中" else if (demo) "刷新演示" else "刷新", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
        item { EnvironmentModeSwitch(onOpenSensors) }
        if (overview != null) item { WeatherAreaControls(overview, onSettings) }
        item {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.padding(vertical = 1.dp)) {
                Canvas(Modifier.size(4.dp)) { drawCircle(visuals.highlightColor) }
                Text(if (demo) "演示数据 · 固定示例，非所在地实况" else "和风天气 · 区域 API，非手机实测", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.error != null) item {
            Surface(shape = RoundedCornerShape(14.dp), color = MaterialTheme.colorScheme.errorContainer) {
                Text(state.error, Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onErrorContainer)
            }
        }
        if (temperature != null) item { TemperatureCard(temperature) { selectedMetric = temperature } }
        if (otherMetrics.isNotEmpty()) {
            item {
                Row(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp),
                    horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                    Text(when (visuals.style) {
                        ThemeStyle.SUNLIT_AMBER -> "环境指标"
                        ThemeStyle.MISTY_DUSK -> "环境一览"
                        ThemeStyle.FLOWING_GLASS -> "环境手记"
                    }, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    Text(if (demo) "轻触了解 · 示例数值" else "轻触了解 · 数据来源", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (visuals.style == ThemeStyle.FLOWING_GLASS) {
                items(otherMetrics.chunked(4), key = { row -> row.joinToString { it.id } }) { group ->
                    AmbientPanel {
                        Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp)) {
                            group.forEachIndexed { index, metric ->
                                MetricRow(metric) { selectedMetric = metric }
                                if (index < group.lastIndex) HorizontalDivider(color = visuals.borderColor)
                            }
                        }
                    }
                }
            } else if (visuals.style == ThemeStyle.MISTY_DUSK) {
                // One connected surface gives the blue / pink design a quieter, ordered grid.
                items(otherMetrics.chunked(4), key = { row -> row.joinToString { it.id } }) { group ->
                    AmbientPanel {
                        Column {
                            group.chunked(2).forEachIndexed { rowIndex, row ->
                                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                                    row.forEachIndexed { index, metric ->
                                        MetricCardContent(metric, Modifier.weight(1f).fillMaxHeight()
                                            .clickable { selectedMetric = metric })
                                        if (index == 0 && row.size == 2) Box(Modifier.width(1.dp).fillMaxHeight()
                                            .background(visuals.borderColor))
                                    }
                                    if (row.size == 1) Spacer(Modifier.weight(1f))
                                }
                                if (rowIndex < (group.size - 1) / 2) HorizontalDivider(color = visuals.borderColor)
                            }
                        }
                    }
                }
            } else {
                items(otherMetrics.chunked(2), key = { row -> row.joinToString { it.id } }) { row ->
                    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        row.forEach { metric ->
                            AmbientPanel(Modifier.weight(1f).fillMaxHeight()
                                .clip(RoundedCornerShape(visuals.radius)).clickable { selectedMetric = metric }) {
                                MetricCardContent(metric)
                            }
                        }
                        if (row.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            }
        } else if (!state.isRefreshing) item {
            Text("暂无区域数据。你仍可以前往探测页查看手机支持的传感器。",
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        snapshot?.notices?.forEach { notice -> item {
            Text(notice, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } }
        if (snapshot != null && !snapshot.isDemo) item {
            Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                Text("数据来源：和风天气 QWeather", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                snapshot.attributions.forEach { attribution ->
                    Text(attribution, style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.clickable {
                            if (attribution.startsWith("https://")) runCatching { uriHandler.openUri(attribution) }
                        }.padding(vertical = 6.dp))
                }
            }
        }
        item {
            Text("来源清晰，每个数值都有来处。", modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    selectedMetric?.let { metric ->
        AlertDialog(
            onDismissRequest = { selectedMetric = null },
            title = { Text(metric.label) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MetricSourceLabel(metric)
                    Text(metric.description)
                    Text(if (metric.id == "altitude") "海拔需要独立的数据源，手机定位高度不等同于准确的地形海拔。"
                        else if (metric.source == DataSource.DEMO) "当前指标是固定演示内容，不代表你所在位置的环境。"
                        else "网络 API 数据代表所选区域的环境，不是手机传感器实测。暂无数据时显示横线，不使用演示值补齐。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { selectedMetric = null }) { Text("知道了") } },
        )
    }
}

@Composable
private fun EnvironmentModeSwitch(onOpenSensors: () -> Unit) {
    val visuals = LocalAmbientVisuals.current
    if (visuals.style == ThemeStyle.MISTY_DUSK) {
        Surface(shape = RoundedCornerShape(10.dp), color = MaterialTheme.colorScheme.surfaceContainerHigh.copy(alpha = .6f)) {
            Row(Modifier.fillMaxWidth().padding(3.dp)) {
                Surface(Modifier.weight(1f), shape = RoundedCornerShape(7.dp), color = MaterialTheme.colorScheme.surface) {
                    Box(Modifier.height(48.dp), contentAlignment = Alignment.Center) {
                        Text("区域环境", style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                }
                Box(Modifier.weight(1f).height(48.dp).clickable(onClick = onOpenSensors), contentAlignment = Alignment.Center) {
                    Text("手机实测", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    } else {
        Column {
            Row(horizontalArrangement = Arrangement.spacedBy(26.dp)) {
                Column {
                    Box(Modifier.height(46.dp), contentAlignment = Alignment.Center) {
                        Text("区域环境", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    }
                    Box(Modifier.width(52.dp).height(2.dp).background(MaterialTheme.colorScheme.primary))
                }
                Box(Modifier.height(48.dp).clickable(onClick = onOpenSensors), contentAlignment = Alignment.Center) {
                    Text("手机实测", style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            HorizontalDivider(color = visuals.borderColor)
        }
    }
}

@Composable
private fun TemperatureCard(metric: EnvironmentMetric, onClick: () -> Unit) {
    val visuals = LocalAmbientVisuals.current
    val content: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth().padding(horizontal = if (visuals.unframedTemperature) 0.dp else 16.dp,
            vertical = if (visuals.unframedTemperature) 4.dp else 12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically) {
                Text(metric.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                MetricSourceLabel(metric)
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(metric.value, fontSize = if (visuals.serifNumbers) 85.sp else 82.sp,
                    lineHeight = 92.sp, letterSpacing = (-4).sp,
                    fontFamily = if (visuals.serifNumbers) FontFamily.Serif else FontFamily.Default,
                    fontWeight = FontWeight.Light)
                Text(metric.unit, Modifier.padding(bottom = 14.dp), fontSize = 25.sp,
                    fontFamily = if (visuals.serifNumbers) FontFamily.Serif else FontFamily.Default,
                    color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Light)
            }
            Text(when (visuals.style) {
                ThemeStyle.SUNLIT_AMBER -> "区域环境数据 · 轻触了解来源"
                ThemeStyle.MISTY_DUSK -> "观察身边，每一刻。"
                ThemeStyle.FLOWING_GLASS -> "周围的世界，值得慢慢观察。"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (visuals.unframedTemperature) Box(Modifier.fillMaxWidth().clickable(onClick = onClick)) { content() }
    else AmbientPanel(Modifier.fillMaxWidth().clip(RoundedCornerShape(visuals.radius)).clickable(onClick = onClick),
        prominent = true, content = content)
}

@Composable
private fun MetricCardContent(metric: EnvironmentMetric, modifier: Modifier = Modifier) {
    val visuals = LocalAmbientVisuals.current
    Column(modifier.padding(horizontal = 13.dp, vertical = 11.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            InstrumentSymbol(metric.id, Modifier.size(16.dp))
            Text(metric.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(metric.value, fontSize = if (metric.value.length > 5) 25.sp else 28.sp,
                lineHeight = 35.sp, fontWeight = FontWeight.Normal,
                fontFamily = if (visuals.serifNumbers && metric.value.any { it.isDigit() }) FontFamily.Serif else FontFamily.Default,
                modifier = Modifier.weight(1f, fill = false))
            if (metric.unit.isNotEmpty()) Text(metric.unit, Modifier.padding(bottom = 4.dp), fontSize = 11.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        MetricSourceLabel(metric)
    }
}

@Composable
private fun MetricRow(metric: EnvironmentMetric, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        InstrumentSymbol(metric.id, Modifier.size(17.dp))
        Text(metric.label, Modifier.weight(1f), style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(metric.value, fontSize = 25.sp, lineHeight = 31.sp, letterSpacing = (-1).sp)
        if (metric.unit.isNotEmpty()) Text(metric.unit, fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        MetricSourceLabel(metric, Modifier.padding(start = 4.dp))
    }
}

@Composable
private fun RefreshSymbol() {
    val color = MaterialTheme.colorScheme.primary
    Canvas(Modifier.size(16.dp)) {
        drawArc(color, 45f, 285f, false, Offset(size.width * .14f, size.height * .14f),
            androidx.compose.ui.geometry.Size(size.width * .72f, size.height * .72f),
            style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round))
        drawLine(color, Offset(size.width * .86f, size.height * .16f), Offset(size.width * .86f, size.height * .43f),
            1.5.dp.toPx(), StrokeCap.Round)
        drawLine(color, Offset(size.width * .86f, size.height * .43f), Offset(size.width * .61f, size.height * .4f),
            1.5.dp.toPx(), StrokeCap.Round)
    }
}

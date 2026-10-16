package com.ambient.app.ui.screens

import android.hardware.SensorManager
import android.os.Build
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.sensor.SensorFeed
import com.ambient.app.domain.sensor.AccelerationMode
import com.ambient.app.domain.sensor.SensorKind
import com.ambient.app.domain.sensor.SensorSample
import com.ambient.app.ui.components.InstrumentSymbol
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.sensors.SensorsViewModel
import com.ambient.app.ui.theme.AmbientPanel
import com.ambient.app.ui.theme.LocalAmbientVisuals
import java.util.Locale
import kotlin.math.abs

@Composable
fun SensorsScreen(
    viewModel: SensorsViewModel,
    modifier: Modifier = Modifier,
    speedContent: @Composable () -> Unit = {},
) {
    val enabled by viewModel.monitoringEnabled.collectAsStateWithLifecycle()
    val accelerationMode by viewModel.accelerationMode.collectAsStateWithLifecycle()
    val visibleFeeds = viewModel.visibleFeeds(accelerationMode)
    val virtualDevice = remember { isProbablyEmulator() }
    val visuals = LocalAmbientVisuals.current
    LifecycleResumeEffect(viewModel) {
        viewModel.start()
        onPauseOrDispose { viewModel.stop() }
    }
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("手机传感器", fontSize = 24.sp, lineHeight = 30.sp, fontWeight = FontWeight.Medium,
                    fontFamily = if (visuals.serifNumbers) FontFamily.Serif else FontFamily.Default)
                Text(if (virtualDevice) "验证数据采集与交互，不代表真实环境。" else "查看实测读数与明确标注的系统计算数据。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (virtualDevice) item {
            Surface(shape = RoundedCornerShape(12.dp), color = MaterialTheme.colorScheme.tertiaryContainer.copy(alpha = .7f)) {
                Text("模拟器 · 虚拟传感器事件，仅用于功能验证",
                    modifier = Modifier.fillMaxWidth().padding(12.dp),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onTertiaryContainer)
            }
        }
        item {
            AmbientPanel {
                Row(Modifier.fillMaxWidth().padding(14.dp), verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                        Text(if (enabled) "传感器采集已开启" else "传感器采集已暂停",
                            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                        Text("${viewModel.feeds.count { it.capability.available }} / ${viewModel.feeds.size} 项能力 · 离页停止",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Switch(checked = enabled, onCheckedChange = viewModel::setMonitoringEnabled,
                        modifier = Modifier.semantics { contentDescription = "实时传感器采集" })
                }
            }
        }
        item { speedContent() }
        items(visibleFeeds, key = { if (it.kind.isAcceleration) "acceleration" else it.kind.name }) { feed ->
            // The subscription lives in each card, so an event does not recompose the entire page.
            SensorCard(feed, enabled, virtualDevice, accelerationMode, viewModel::setAccelerationMode)
        }
        item {
            Text(if (virtualDevice) "曲线横轴使用传感器事件时间，最多显示最近 60 个点。模拟器事件仅验证交互与数据流程，不能用于判断真实环境。"
                else "曲线横轴使用传感器事件时间，最多显示最近 60 个点。读数受设备精度、姿态和周围干扰影响；本页不提供室外温度、湿度或空气质量的实测。",
                modifier = Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun SensorCard(
    feed: SensorFeed,
    enabled: Boolean,
    virtualDevice: Boolean,
    accelerationMode: AccelerationMode,
    onAccelerationModeChange: (AccelerationMode) -> Unit,
) {
    val reading by feed.state.collectAsStateWithLifecycle()
    var showMeaning by remember { mutableStateOf(false) }
    val vector = feed.kind == SensorKind.MAGNETIC_FIELD || feed.kind.isAcceleration || feed.kind == SensorKind.GYROSCOPE
    val linear = feed.kind == SensorKind.LINEAR_ACCELERATION
    val readingSource = when {
        virtualDevice -> "虚拟"
        linear -> "系统计算"
        else -> "实测"
    }
    val value = if (vector) reading.magnitude else reading.values.firstOrNull()
    val hasReading = reading.timestampNanos != null && value?.isFinite() == true
    val colors = MaterialTheme.colorScheme
    AmbientPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically) {
                InstrumentSymbol(feed.kind.name, color = if (feed.capability.available) colors.primary else colors.onSurfaceVariant)
                Text(feed.kind.displayName, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { showMeaning = true }) { Text("了解") }
            }
            if (feed.kind.isAcceleration) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    AccelerationMode.entries.forEach { mode ->
                        FilterChip(
                            selected = accelerationMode == mode,
                            onClick = { onAccelerationModeChange(mode) },
                            label = { Text(mode.label) },
                            modifier = Modifier.weight(1f).heightIn(min = 48.dp)
                                .semantics { contentDescription = "加速度${mode.label}" },
                        )
                    }
                }
                Text(if (linear) "静止时通常接近 0；系统已去除重力，仍可能有少量偏差。"
                    else "保留重力分量；静止时合成值通常接近 9.8 m/s²。",
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            }
            if (!feed.capability.available) {
                StatusBadge("设备不支持")
                Text(if (linear) "此设备未提供系统去重力数据。可切换「含重力」查看原始加速度。"
                    else "此设备未提供该传感器，无法读取实测数据。",
                    style = MaterialTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
            } else {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    StatusBadge(when {
                        virtualDevice && linear -> "虚拟数据 · 去重力"
                        virtualDevice && feed.kind.isAcceleration -> "虚拟传感器 · 含重力"
                        virtualDevice -> "虚拟传感器"
                        linear -> "系统计算 · 去重力"
                        feed.kind.isAcceleration -> "手机实测 · 含重力"
                        else -> "手机实测"
                    }, accent = !virtualDevice && !linear)
                    Text(when {
                        !enabled -> "已暂停"
                        reading.error != null -> "采集异常"
                        reading.isListening && !hasReading -> "等待读数"
                        reading.isListening && reading.accuracy == SensorManager.SENSOR_STATUS_UNRELIABLE -> "采集中 · 精度不可靠"
                        reading.isListening && reading.accuracy == SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "采集中 · 精度较低"
                        reading.isListening -> "采集中"
                        else -> "等待启动"
                    }, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (vector) Text("合成强度 · 计算值", style = MaterialTheme.typography.labelSmall,
                        color = colors.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(if (hasReading) formatSensorValue(value) else "—", fontSize = 40.sp,
                            lineHeight = 48.sp, fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Normal,
                            modifier = Modifier.weight(1f, fill = false))
                        Text(feed.kind.unit, Modifier.padding(bottom = 7.dp), style = MaterialTheme.typography.titleSmall,
                            color = colors.onSurfaceVariant)
                    }
                    if (!hasReading) Text(if (enabled) "等待第一笔${readingSource}读数"
                        else "开启采集后显示${readingSource}读数",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                    else if (!enabled || !reading.isListening) Text("保留最后一次${readingSource}读数", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                if (vector && reading.values.size >= 3) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(7.dp)) {
                        listOf("X", "Y", "Z").forEachIndexed { index, axis ->
                            Surface(modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp),
                                color = colors.onSurface.copy(alpha = .045f)) {
                                Column(Modifier.padding(horizontal = 9.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                                    Text(axis, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                                    Text(formatAxisValue(reading.values[index]), style = MaterialTheme.typography.bodyMedium,
                                        fontFamily = FontFamily.Monospace, fontWeight = FontWeight.Medium)
                                }
                            }
                        }
                    }
                    Text("XYZ 为${if (virtualDevice) "虚拟" else if (linear) "系统去重力" else "实测"}分量 · ${feed.kind.unit}",
                        style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                }
                if (reading.samples.isNotEmpty()) {
                    HorizontalDivider(color = colors.outlineVariant.copy(alpha = .5f))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(if (vector) "合成强度变化 · 计算值" else if (virtualDevice) "虚拟读数变化" else "实测变化", style = MaterialTheme.typography.labelSmall,
                            color = colors.onSurfaceVariant)
                        Text(feed.kind.unit, style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                    }
                    SensorChart(reading.samples)
                }
                if (reading.accuracy != null) Text("精度状态：${accuracyLabel(reading.accuracy!!)}",
                    style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                reading.timestampNanos?.let { timestamp ->
                    Text("事件时间 ${String.format(Locale.ROOT, "%.3f", timestamp / 1_000_000_000.0)} s · 设备启动后的时间",
                        style = MaterialTheme.typography.labelSmall, color = colors.onSurfaceVariant)
                }
                reading.error?.let { error ->
                    Text(error, style = MaterialTheme.typography.bodySmall, color = colors.error)
                }
                if (hasReading && reading.error == null) {
                    val warning = when (reading.accuracy) {
                        SensorManager.SENSOR_STATUS_UNRELIABLE -> if (linear)
                            "系统将去重力结果标记为精度不可靠，数值仅供参考。此状态不提供具体原因，等待不保证改善。"
                            else "系统将此读数标记为精度不可靠，数值仅供参考。此状态不提供具体原因。"
                        SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "系统报告当前精度较低，数值仅供参考。"
                        else -> null
                    }
                    warning?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant) }
                }
            }
        }
    }
    if (showMeaning) SensorMeaningDialog(feed, virtualDevice, onDismiss = { showMeaning = false })
}

@Composable
private fun SensorChart(allSamples: List<SensorSample>) {
    val samples = allSamples.takeLast(60).filter { it.value.isFinite() }
    val lineColor = MaterialTheme.colorScheme.primary
    val gridColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = .4f)
    val firstTimestamp = samples.firstOrNull()?.timestampNanos ?: 0L
    val timeSpan = ((samples.lastOrNull()?.timestampNanos ?: firstTimestamp) - firstTimestamp).coerceAtLeast(1L)
    val minValue = samples.minOfOrNull { it.value } ?: 0f
    val maxValue = samples.maxOfOrNull { it.value } ?: 1f
    val valueRange = (maxValue - minValue).coerceAtLeast((abs(maxValue) * .02f).coerceAtLeast(.01f))
    val middle = (minValue + maxValue) * .5f
    val lower = middle - valueRange * .65f
    val upper = middle + valueRange * .65f
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.fillMaxWidth().height(96.dp)) {
            val top = 5.dp.toPx()
            val bottom = size.height - 5.dp.toPx()
            for (i in 0..2) {
                val y = top + (bottom - top) * i / 2
                drawLine(gridColor, Offset(0f, y), Offset(size.width, y), 1.dp.toPx())
            }
            fun point(sample: SensorSample) = Offset(
                ((sample.timestampNanos - firstTimestamp).toDouble() / timeSpan * size.width).toFloat().coerceIn(0f, size.width),
                bottom - ((sample.value - lower) / (upper - lower)) * (bottom - top),
            )
            if (samples.size >= 2) {
                val path = Path().apply {
                    samples.forEachIndexed { index, sample ->
                        val point = point(sample)
                        if (index == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
                    }
                }
                drawPath(path, lineColor, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
            }
            samples.lastOrNull()?.let { drawCircle(lineColor, 3.dp.toPx(), point(it)) }
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            Text(if (samples.size > 1) "−${String.format(Locale.ROOT, "%.1f", timeSpan / 1_000_000_000.0)} s" else "首个读数",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("最近一次采样", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Text("范围 ${formatAxisValue(minValue)} ~ ${formatAxisValue(maxValue)} · 纵轴自动缩放",
            style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SensorMeaningDialog(feed: SensorFeed, virtualDevice: Boolean, onDismiss: () -> Unit) {
    val latestReading by feed.state.collectAsStateWithLifecycle()
    // Keep selectable diagnostic text stable while the user copies it.
    var reading by remember(feed) { mutableStateOf(latestReading) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(feed.kind.displayName) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (virtualDevice) Text("当前为模拟器虚拟传感器，以下读数和硬件信息仅用于功能验证。")
                Text(feed.kind.description)
                if (feed.kind == SensorKind.ACCELEROMETER) Text("加速度计的读数包含重力，静置时合成值通常接近当地重力加速度，不代表手机正在运动。")
                if (feed.kind == SensorKind.LINEAR_ACCELERATION) Text("数据来自 Android 的线性加速度通道，由系统计算去除重力，Ambient 不将原始模长直接减去 9.8，也不通过积分估算速度。")
                if (feed.kind == SensorKind.MAGNETIC_FIELD) Text("磁体、金属、电器和手机配件会影响读数。这里显示磁场强度，不将其当作已校准的指南针方位。")
                if (feed.capability.available) {
                    HorizontalDivider()
                    Text("传感器：${feed.capability.name ?: "设备未提供名称"}")
                    Text("制造商：${feed.capability.vendor ?: "未知"}")
                    feed.capability.resolution?.let { Text("标称分辨率：${formatAxisValue(it)} ${feed.kind.unit}") }
                    feed.capability.maximumRange?.let { Text("标称量程：${formatAxisValue(it)} ${feed.kind.unit}") }
                    HorizontalDivider()
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                        Text("系统状态详情", Modifier.weight(1f), style = MaterialTheme.typography.titleSmall)
                        TextButton(onClick = { reading = latestReading }) { Text("刷新状态") }
                    }
                    Text("以下是打开详情或点击刷新时的状态快照，可长按复制。数据事件与精度回调的报告可能不同；没有回调不代表精度合格。",
                        style = MaterialTheme.typography.bodySmall)
                    SelectionContainer {
                        Text(buildString {
                            appendLine("传感器：${feed.capability.name ?: "设备未提供名称"}")
                            appendLine("制造商：${feed.capability.vendor ?: "未知"}")
                            appendLine("通道：${feed.kind.name}")
                            appendLine("当前精度：${accuracyDiagnostic(reading.accuracy)}")
                            appendLine("最近数据事件的精度：${accuracyDiagnostic(reading.eventAccuracy)}")
                            appendLine("最近精度回调：${accuracyDiagnostic(reading.callbackAccuracy)}")
                            appendLine("事件时间(ns)：${reading.timestampNanos ?: "尚无有效读数"}")
                            appendLine("XYZ：${reading.values.joinToString()} ${feed.kind.unit}")
                            append("采集错误：${reading.error ?: "无"}")
                        }, style = MaterialTheme.typography.bodySmall)
                    }
                } else Text(if (feed.kind == SensorKind.LINEAR_ACCELERATION)
                    "这部手机未提供线性加速度通道。可以切换到含重力的原始加速度；Ambient 不自行生成去重力读数。"
                    else "这部手机没有提供此传感器。Ambient 不使用估算数值替代。")
                Text("XYZ 使用 Android 设备坐标系：X 向屏幕右侧、Y 向手机顶部、Z 从屏幕朝外。")
                if (feed.kind == SensorKind.MAGNETIC_FIELD || feed.kind.isAcceleration || feed.kind == SensorKind.GYROSCOPE) {
                    Text("合成强度是根据三轴分量计算的模长 √(X² + Y² + Z²)，不是额外的传感器实测。")
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } },
    )
}

private fun formatSensorValue(value: Float): String = when {
    !value.isFinite() -> "—"
    abs(value) >= 1000f -> String.format(Locale.ROOT, "%.0f", value)
    abs(value) >= 100f -> String.format(Locale.ROOT, "%.1f", value)
    else -> String.format(Locale.ROOT, "%.2f", value)
}

private fun formatAxisValue(value: Float): String = if (value.isFinite()) String.format(Locale.ROOT, "%.2f", value) else "—"

private fun accuracyLabel(accuracy: Int): String = when (accuracy) {
    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高"
    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中"
    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低"
    SensorManager.SENSOR_STATUS_UNRELIABLE -> "系统标记为不可靠"
    SensorManager.SENSOR_STATUS_NO_CONTACT -> "尚未接触"
    else -> "设备未提供"
}

private fun accuracyDiagnostic(accuracy: Int?): String =
    accuracy?.let { "${accuracyLabel(it)}（原始状态 $it）" } ?: "尚未收到报告"

/** Conservative UI labeling only; sensor support still comes from SensorManager. */
internal fun isProbablyEmulator(): Boolean {
    val fingerprint = Build.FINGERPRINT.lowercase(Locale.ROOT)
    val model = Build.MODEL.lowercase(Locale.ROOT)
    val hardware = Build.HARDWARE.lowercase(Locale.ROOT)
    val product = Build.PRODUCT.lowercase(Locale.ROOT)
    return fingerprint.startsWith("generic") || fingerprint.contains("emulator") ||
        model.contains("generic") || model.contains("emulator") || model.contains("android sdk") ||
        model.contains("sdk_gphone") || hardware.contains("goldfish") || hardware.contains("ranchu") ||
        product.startsWith("sdk") || product.contains("emulator") || product.contains("vbox") ||
        Build.MANUFACTURER.equals("Genymotion", ignoreCase = true)
}

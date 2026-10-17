package com.ambient.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.sky.*
import com.ambient.app.ui.sky.SkyUiState
import com.ambient.app.ui.theme.AmbientPanel
import com.ambient.app.ui.theme.LocalAmbientVisuals
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin

@Composable
fun SkyScreen(state: SkyUiState, onRefresh: () -> Unit, onOpenSensors: () -> Unit,
              modifier: Modifier = Modifier, uv: EnvironmentMetric? = null, uvTime: Instant? = null,
              uvCached: Boolean = false) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { value = Instant.now(); delay(30_000) }
        }
    }
    val snapshot = state.result.snapshot
    val uri = LocalUriHandler.current
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("SUNLIGHT & SKY", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary)
                    Text(snapshot?.area?.label ?: "所选区域", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text("刷新天文")
                }
            }
        }
        item {
            Text("天文计算 · 和风天气 · 非手机实测", style = MaterialTheme.typography.labelLarge,
                color = MaterialTheme.colorScheme.primary)
            Text("根据地点与时间计算。云层、山体和建筑遮挡会影响实际光照。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.result.messages.forEach { message -> item {
            Text(message, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
        } }
        if (snapshot == null) item {
            SkyPanel {
                Text(if (state.loading) "正在获取天文计算数据…" else "暂时没有天文数据", style = MaterialTheme.typography.titleMedium)
                Text("返回总览，在设置中导入和风凭据、启用区域 API，并选择城市或使用当前位置。天文接口还需对应权限。",
                    style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (snapshot != null) {
            val zone = ZoneId.of(snapshot.zoneId)
            val day = daylight(snapshot, now)
            val expired = now.atZone(zone).toLocalDate().toString() != snapshot.date
            item {
                Text("${snapshot.date} · ${if (snapshot.deviceZoneFallback) "设备时区" else "地区时区"} ${snapshot.zoneId}",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (state.result.isCached) Text("当日缓存 · 保留各项原获取时间", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.deviceZoneFallback) Text("尚无地区时区，暂按设备时区查询；跨时区时请重新搜索并选择城市。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (snapshot.area.isMock) Text("模拟定位 · 测试地点，非手机真实位置", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.labelMedium)
            }
            item {
                SkyPanel {
                    Text("今日白昼", style = MaterialTheme.typography.titleMedium)
                    Text(day.duration?.let(::durationText) ?: "—", style = MaterialTheme.typography.displaySmall)
                    DaylightArc(day.progress)
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        SkyNumber("日出", timeText(snapshot.sun?.sunrise, zone))
                        SkyNumber("日落", timeText(snapshot.sun?.sunset, zone))
                    }
                    HorizontalDivider()
                    Text(day.headline, style = MaterialTheme.typography.labelLarge)
                    day.remaining?.let { Text(durationText(it), style = MaterialTheme.typography.headlineMedium) }
                    Text("弧线表示日出至日落的时间进度，不代表太阳实际轨迹或晴天时长。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("无日出或日落数据时留空，不据此推断极昼或极夜。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FetchCaption(snapshot.sun?.fetchedAt, zone)
                }
            }
            item {
                SkyPanel {
                    Text("太阳位置", style = MaterialTheme.typography.titleMedium)
                    val solar = snapshot.solar
                    val oldAngle = solar == null || expired || Duration.between(Instant.ofEpochMilli(solar.calculatedFor), now).seconds !in 0..599
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(32.dp)) {
                        SkyNumber("高度角", solar?.elevation?.let(::degrees) ?: "—")
                        SkyNumber("方位角", solar?.azimuth?.let(::degrees) ?: "—")
                    }
                    Text("高度角以地平线为 0°，负值表示位于地平线下；方位角以地理北为 0°、顺时针增加。与手机磁北指南针分开显示。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (solar != null) {
                        Text("计算对应 ${instantText(solar.calculatedFor, zone)}${if (oldAngle) " · 较早计算，请刷新" else ""}",
                            style = MaterialTheme.typography.labelMedium)
                        Text(if (solar.usedPositioningHeight)
                            "高度参数 ${solar.referenceHeightMeters} m · 定位报告的 WGS84 高度，近似参考，非地形海拔"
                            else "高度参数 0 m · 参考假设，未取得当地高度", style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant)
                        snapshot.area.verticalAccuracyMeters?.takeIf { solar.usedPositioningHeight && it.isFinite() && it >= 0 }?.let {
                            Text("系统报告的垂直精度 ±${String.format(Locale.getDefault(), "%.0f", it)} m",
                                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Text("使用总览的“当前位置”可尝试取得定位高度；本页不持续定位。", style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FetchCaption(solar?.fetchedAt, zone)
                }
            }
            item {
                SkyPanel {
                    val phase = currentMoon(snapshot, now)
                    Text("月相", style = MaterialTheme.typography.titleMedium)
                    Text(phase?.name?.takeIf { it.isNotBlank() } ?: "—", style = MaterialTheme.typography.headlineMedium)
                    Text(phase?.illumination?.let { "月面照明比例 ${String.format(Locale.getDefault(), "%.0f", it)}%" }
                        ?: "当前时段暂无月面照明比例", style = MaterialTheme.typography.bodyMedium)
                    Text("月面照明比例是天文计算，不是地面照度，也不能判断云层是否遮住月亮。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    phase?.let { Text("对应时段 ${timeText(it.time, zone)}", style = MaterialTheme.typography.labelSmall) }
                    FetchCaption(snapshot.moon?.fetchedAt, zone)
                }
            }
            item {
                SkyPanel {
                    Text("紫外线与手机光照", style = MaterialTheme.typography.titleMedium)
                    val metric = uv?.takeIf { it.source == DataSource.REGIONAL_API }
                    Text(metric?.let { "${it.value} ${it.unit}" } ?: "— UVI", style = MaterialTheme.typography.headlineMedium)
                    Text("紫外线 · 区域 API，非手机实测", style = MaterialTheme.typography.labelMedium)
                    uvTime?.takeIf { metric != null }?.let {
                        Text("${if (uvCached) "天气缓存 · " else ""}获取于 ${instantText(it.toEpochMilli(), zone)}",
                            style = MaterialTheme.typography.labelSmall)
                    }
                    Text("沿用当前区域天气数据，不额外请求。手机光照传感器的 lx 无法直接换算为紫外线指数。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextButton(onClick = onOpenSensors) { Text("查看手机实测光照") }
                }
            }
            item {
                val sources = (snapshot.sun?.attributions.orEmpty() + snapshot.moon?.attributions.orEmpty() +
                    snapshot.solar?.attributions.orEmpty()).distinct()
                Text("日出日落、月相和太阳角度：天文计算 · 和风天气。倒计时、白昼时长与进度：本机时间计算。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (sources.isNotEmpty()) Text(sources.joinToString("\n"), style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                TextButton(onClick = { uri.openUri("https://dev.qweather.com/docs/api/astronomy/") }) { Text("查看数据来源说明") }
            }
        }
        item { Spacer(Modifier.height(12.dp)) }
    }
}

@Composable private fun SkyPanel(content: @Composable ColumnScope.() -> Unit) {
    AmbientPanel { Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp), content = content) }
}
@Composable private fun SkyNumber(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
    }
}
@Composable private fun FetchCaption(at: Long?, zone: ZoneId) {
    Text(at?.let { "获取于 ${instantText(it, zone)} · 可使用当日缓存" } ?: "暂无数据",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
@Composable private fun DaylightArc(progress: Float?) {
    val accent = MaterialTheme.colorScheme.primary
    val track = LocalAmbientVisuals.current.borderColor
    Canvas(Modifier.fillMaxWidth().height(108.dp).semantics {
        contentDescription = progress?.let { "白昼时间进度 ${(it * 100).toInt()}%" } ?: "暂无白昼进度"
    }) {
        val radius = minOf(size.width / 2 - 12.dp.toPx(), size.height - 12.dp.toPx())
        val origin = Offset(size.width / 2 - radius, size.height - radius - 4.dp.toPx())
        val arcSize = Size(radius * 2, radius * 2)
        drawArc(track, 180f, 180f, false, origin, arcSize, style = Stroke(2.dp.toPx(), cap = StrokeCap.Round))
        if (progress != null) {
            drawArc(accent, 180f, 180f * progress, false, origin, arcSize,
                style = Stroke(3.dp.toPx(), cap = StrokeCap.Round))
            val angle = Math.PI * (1 - progress)
            drawCircle(accent, 6.dp.toPx(), Offset(size.width / 2 + radius * cos(angle).toFloat(),
                size.height - 4.dp.toPx() - radius * sin(angle).toFloat()))
        }
    }
}
private fun instantText(at: Long, zone: ZoneId) = DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(zone).format(Instant.ofEpochMilli(at))
private fun timeText(time: String?, zone: ZoneId) = astronomyTime(time)?.let { DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(it) } ?: "—"
private fun degrees(value: Double) = String.format(Locale.getDefault(), "%.1f°", value)
private fun durationText(value: Duration): String {
    val minutes = (value.seconds.coerceAtLeast(0) + 59) / 60
    return "${minutes / 60} 小时 ${minutes % 60} 分钟"
}

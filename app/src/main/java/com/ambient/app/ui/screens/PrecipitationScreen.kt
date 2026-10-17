package com.ambient.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.app.domain.precipitation.*
import com.ambient.app.ui.precipitation.PrecipitationUiState
import com.ambient.app.ui.theme.AmbientPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToInt

@Composable
fun PrecipitationScreen(state: PrecipitationUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle, state.result.snapshot) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { value = Instant.now(); delay(30_000) }
        }
    }
    var rangeName by rememberSaveable { mutableStateOf(ForecastRange.TWO_HOURS.name) }
    val range = ForecastRange.valueOf(rangeName)
    val snapshot = state.result.snapshot
    val zone = snapshot?.area?.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
    val displayZone = zone ?: ZoneId.systemDefault()
    fun time(at: Long) = DateTimeFormatter.ofPattern("MM-dd HH:mm", Locale.CHINA).withZone(displayZone).format(Instant.ofEpochMilli(at))
    val forecast = if (range == ForecastRange.TWO_HOURS) snapshot?.minutely else snapshot?.hourly
    val points = forecastPoints(forecast, range, now)
    val stale = forecast?.let { forecastStale(it, range, now) } == true
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("PRECIPITATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(snapshot?.area?.label ?: "所选区域", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRefresh, enabled = !state.loading) {
                    if (state.loading) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp) else Text("刷新预报")
                }
            }
            Text("区域预报 · 和风天气 · 非手机实测", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary)
        }
        item {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ForecastRange.entries.forEach { choice ->
                    FilterChip(range == choice, { rangeName = choice.name }, { Text(choice.label) }, Modifier.weight(1f))
                }
            }
            Text(if (range == ForecastRange.TWO_HOURS) "精细降水 · 每 5 分钟一个预报点" else "降水趋势 · 每小时一个预报点",
                style = MaterialTheme.typography.titleSmall)
            Text(if (range == ForecastRange.TWO_HOURS) "中国地区 · 公里级预报，无法保证精确到楼栋。" else
                "时间越远，不确定性越大；小时预报不能确定具体哪一分钟开始下雨。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.result.messages.forEach { message -> item { Text(message, color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall) } }
        if (forecast == null) item { RainPanel {
            Text(if (state.loading) "正在获取降水预报…" else "暂无此类降水预报", style = MaterialTheme.typography.titleMedium)
            Text("暂无数据不代表不会下雨。请检查网络、区域设置，以及凭据的“分钟降水”和“天气预报”权限。",
                style = MaterialTheme.typography.bodyMedium)
        } }
        if (forecast != null) {
            item {
                RainPanel {
                    Text(if (range == ForecastRange.TWO_HOURS) "未来两小时" else "未来 ${range.hours} 小时",
                        style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
                    Text(when {
                        points.isEmpty() -> "预报时段已结束"
                        points.none { it.amountMm != null } -> "降水量暂缺"
                        stale -> "旧预报 · 请刷新后参考"
                        range == ForecastRange.TWO_HOURS && !forecast.summary.isNullOrBlank() -> forecast.summary
                        range == ForecastRange.TWO_HOURS -> "查看短时降水变化"
                        else -> "查看逐时降水变化"
                    }, style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Medium)
                    if (range == ForecastRange.TWO_HOURS && !stale && points.any { it.amountMm != null } &&
                        !forecast.summary.isNullOrBlank()) Text("以上为接口更新时间对应的预报描述，非实时倒计时。",
                            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (points.isEmpty()) Text("已隐藏过期预报图与旧降水提示，避免把过去的预报当作未来。")
                    if (points.isNotEmpty()) Text("预报点时间 ${time(points.first().time)} — ${time(points.last().time)}",
                        style = MaterialTheme.typography.bodySmall)
                    Text("获取时间 ${time(forecast.fetchedAt)}${if (state.result.isCached) " · 本地缓存" else ""}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(forecast.updatedAt?.let { "预报更新时间 ${time(it)}" } ?: "接口未提供预报发布时间；获取时间不等于发布时间。",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${if (zone == null) "设备时区（地区时区未知）" else "地区时区"} · ${displayZone.id}",
                        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (snapshot?.area?.isMock == true) Text("模拟定位 · 非手机真实位置", color = MaterialTheme.colorScheme.error)
                }
            }
            if (points.isNotEmpty()) item {
                RainPanel {
                    key(range) { RainChart(points, range, displayZone) }
                }
            }
            item { RainPanel {
                Text("如何阅读", style = MaterialTheme.typography.titleSmall)
                Text(if (range == ForecastRange.TWO_HOURS)
                    "柱高表示每 5 分钟累计降水量（mm），不是每小时雨强；此接口不提供降水概率。" else
                    "柱高表示每小时累计降水量（mm）；降水概率单独显示。两个时间尺度不直接拼接或插值。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("零值表示接口预测该时段无降水；“—”表示数据缺失。雨、雪等类型按接口返回标注。间隔缺口保留，不补成零。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("选定地点的预报，不代表整个城市；移动后请返回总览重新使用当前位置。", style = MaterialTheme.typography.bodySmall)
            } }
            item {
                Text("数据来源：和风天气 QWeather", style = MaterialTheme.typography.labelMedium)
                forecast.attributions.forEach { Text(it, style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant) }
            }
        }
    }
}

@Composable
private fun RainPanel(content: @Composable ColumnScope.() -> Unit) {
    AmbientPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}

/** Separate interval bars: proportional time spacing, no smoothed curve or invented intermediate values. */
@Composable
private fun RainChart(points: List<PrecipitationPoint>, range: ForecastRange, zone: ZoneId) {
    var selectedTime by rememberSaveable(range.name, points.first().time) { mutableLongStateOf(points.first().time) }
    val selected = points.firstOrNull { it.time == selectedTime } ?: points.first()
    val peak = (points.mapNotNull { it.amountMm }.maxOrNull() ?: 0.0).coerceAtLeast(0.1)
    val primary = MaterialTheme.colorScheme.primary
    val subdued = MaterialTheme.colorScheme.onSurfaceVariant
    val step = range.stepMinutes * 60_000L
    val total = ((points.last().time - points.first().time) / step + 1).toInt().coerceIn(1, 145)
    val labels = DateTimeFormatter.ofPattern(if (range == ForecastRange.TWO_HOURS) "HH:mm" else "dd日 HH:mm", Locale.CHINA).withZone(zone)
    fun mm(value: Double?) = value?.let { String.format(Locale.CHINA, "%.2f", it) } ?: "—"
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("${range.stepMinutes} 分钟累计降水量 · mm", style = MaterialTheme.typography.labelLarge)
        Text("纵轴 0 — ${mm(peak)} mm · 左右滑动，点按查看",
            style = MaterialTheme.typography.labelSmall, color = subdued)
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val width = (maxWidth / total).coerceAtLeast(if (range == ForecastRange.TWO_HOURS) 26.dp else 48.dp)
            Row(Modifier.horizontalScroll(rememberScrollState()).height(174.dp), verticalAlignment = Alignment.Bottom) {
                points.forEachIndexed { index, point ->
                    if (index > 0) {
                        val gap = ((point.time - points[index - 1].time) / step - 1).toInt().coerceIn(0, 144)
                        if (gap > 0) Box(Modifier.width(width * gap).fillMaxHeight(), contentAlignment = Alignment.Center) {
                            Text("缺测", style = MaterialTheme.typography.labelSmall, color = subdued)
                        }
                    }
                    Column(Modifier.width(width).fillMaxHeight().clip(RoundedCornerShape(7.dp))
                        .background(if (point.time == selectedTime) primary.copy(alpha = .12f) else androidx.compose.ui.graphics.Color.Transparent)
                        .clickable(role = Role.Button) { selectedTime = point.time }
                        .semantics { contentDescription = "${labels.format(Instant.ofEpochMilli(point.time))}，降水量 ${mm(point.amountMm)} 毫米"
                            this.selected = point.time == selectedTime },
                        verticalArrangement = Arrangement.Bottom, horizontalAlignment = Alignment.CenterHorizontally) {
                        Box(Modifier.height(132.dp).fillMaxWidth(), contentAlignment = Alignment.BottomCenter) {
                            if (point.amountMm == null) Text("—", color = subdued)
                            else Box(Modifier.width((width - 8.dp).coerceAtMost(30.dp))
                                .height((128 * (point.amountMm / peak).toFloat()).dp.coerceAtLeast(2.dp))
                                .clip(RoundedCornerShape(topStart = 5.dp, topEnd = 5.dp))
                                .background(Brush.verticalGradient(listOf(primary, primary.copy(alpha = .3f)))))
                        }
                        HorizontalDivider(color = subdued.copy(alpha = .4f))
                        Text(if (index % (if (range == ForecastRange.TWO_HOURS) 6 else 3) == 0)
                            DateTimeFormatter.ofPattern("HH:mm").withZone(zone).format(Instant.ofEpochMilli(point.time)) else "",
                            fontSize = 9.sp, maxLines = 1, modifier = Modifier.height(25.dp))
                    }
                }
            }
        }
        HorizontalDivider()
        Text("选中预报时间 ${labels.format(Instant.ofEpochMilli(selected.time))}", style = MaterialTheme.typography.labelLarge)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(mm(selected.amountMm), style = MaterialTheme.typography.headlineLarge)
            Text("mm / ${range.stepMinutes} 分钟", Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.bodySmall)
        }
        Text("降水类型：${precipitationType(selected.type)}", style = MaterialTheme.typography.bodyMedium)
        Text(if (range == ForecastRange.TWO_HOURS) "降水概率：此接口未提供" else
            "降水概率：${selected.probability?.let { "${(it * 100).roundToInt()}%" } ?: "—"}", style = MaterialTheme.typography.bodyMedium)
    }
}

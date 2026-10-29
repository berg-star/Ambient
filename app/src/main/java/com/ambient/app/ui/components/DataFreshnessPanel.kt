package com.ambient.app.ui.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.app.domain.environment.*
import com.ambient.app.ui.overview.EnvironmentUiState
import com.ambient.app.ui.theme.AmbientPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/** Ticks only this small timestamp surface, while visible; does not fetch or locate. */
@Composable
private fun freshnessNow(data: Any?): Instant {
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { value = Instant.now(); delay(60_000) }
        }
    }
    // A new response can arrive between minute ticks. Compare it with the current clock,
    // not the older tick (which would falsely label a normal response as future-dated).
    return remember(now, data) { Instant.now() }
}

private fun displayTime(time: Instant): String = DateTimeFormatter.ofPattern("MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(time)

@Composable
fun LocationFreshnessLabel(state: EnvironmentUiState) {
    val area = state.preferences.area
    val text = when {
        state.isLocating -> "正在定位 · 原区域暂时保留"
        !state.preferences.enabled -> "演示区域 · 未使用定位"
        area == null -> "尚未选择区域"
        !area.fromDevice -> "手选区域 · 非手机定位"
        else -> (if (area.isMock) "上次模拟定位" else "上次定位") +
            (locationFixTime(area)?.let { " · ${displayTime(it)}" } ?: " · 时间未记录")
    }
    Text(text, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
fun DataFreshnessPanel(state: EnvironmentUiState) {
    val snapshot = state.snapshot ?: return
    if (snapshot.isDemo) {
        Text("演示数据 · 固定示例，不表示当前环境", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        return
    }
    var expanded by remember { mutableStateOf(false) }
    val now = freshnessNow(snapshot)
    val groups = listOf("天气" to snapshot.metrics.filter { it.source == DataSource.REGIONAL_API && it.id !in airMetricIds },
        "空气质量" to snapshot.metrics.filter { it.source == DataSource.REGIONAL_API && it.id in airMetricIds })
    AmbientPanel(Modifier.fillMaxWidth().clickable { expanded = true }) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(5.dp)) {
            if (state.isRefreshing) Text("正在刷新 · 暂显已有数据", style = MaterialTheme.typography.labelSmall)
            else if (state.error != null && state.isCached) Text("刷新未完成 · 保留上次数据",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.error)
            groups.forEach { (label, metrics) ->
                // Oldest available time represents this source batch conservatively; missing fields remain missing.
                val metric = metrics.filter { it.value != "—" && it.value.isNotBlank() }
                    .minByOrNull { it.fetchedAtMillis ?: Long.MIN_VALUE }
                val timing = metric?.let { metricTiming(it, snapshot, now) }
                val status = timing?.let { freshnessLabel(it, state.isCached) } ?: "暂无有效数据"
                val time = timing?.fetchedAt?.let { " · ${displayTime(it)} 获取" }.orEmpty()
                Text("$label · $status$time", style = MaterialTheme.typography.labelSmall,
                    color = if (timing?.freshness in listOf(Freshness.OLD, Freshness.CLOCK_ERROR))
                        MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text("数据时间与定位说明  ›", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
        }
    }
    if (expanded) AlertDialog(onDismissRequest = { expanded = false }, title = { Text("数据时间与定位") },
        text = { Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            LocationFreshnessLabel(state)
            val fix = locationFixTime(state.preferences.area)
            if (fix?.isAfter(now) == true) Text("定位时间晚于设备当前时间，请核对时间设置。", color = MaterialTheme.colorScheme.error)
            Text(if (state.preferences.area?.fromDevice == true)
                "这是上次单次定位的区域，未持续追踪。移动后请重新使用当前位置；刷新天气不会更新位置或高度。"
                else "天气代表手动选择的区域，不表示手机此刻的位置。")
            groups.forEach { (label, metrics) ->
                Text(label, style = MaterialTheme.typography.titleSmall)
                val metric = metrics.filter { it.value != "—" }.minByOrNull { it.fetchedAtMillis ?: Long.MIN_VALUE }
                if (metric == null) Text("暂无有效数据")
                else MetricTimingText(metric, snapshot, state.isCached, now)
            }
            Text("“获取较早”是本应用的刷新提醒：天气 10 分钟、空气质量 1 小时。缓存不一定过期；刚获取也不等于刚观测。",
                style = MaterialTheme.typography.bodySmall)
            Text("时间按设备时区 ${ZoneId.systemDefault().id} 显示。", style = MaterialTheme.typography.labelSmall)
        } }, confirmButton = { TextButton(onClick = { expanded = false }) { Text("知道了") } })
}

@Composable
fun MetricFreshnessDetails(metric: EnvironmentMetric, snapshot: EnvironmentSnapshot?, isCached: Boolean) {
    MetricTimingText(metric, snapshot, isCached, freshnessNow(metric to snapshot?.updatedAt))
}

@Composable
private fun MetricTimingText(metric: EnvironmentMetric, snapshot: EnvironmentSnapshot?, isCached: Boolean, now: Instant) {
    val timing = metricTiming(metric, snapshot, now)
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(if (metric.source == DataSource.POSITIONING && timing.freshness == Freshness.OLD)
            "上次定位较早 · 移动后请重新定位" else freshnessLabel(timing, isCached && metric.source == DataSource.REGIONAL_API), style = MaterialTheme.typography.labelMedium,
            color = if (timing.freshness in listOf(Freshness.OLD, Freshness.CLOCK_ERROR))
                MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary)
        if (timing.freshness !in listOf(Freshness.MISSING, Freshness.DEMO)) {
            if (metric.source == DataSource.POSITIONING) {
                Text("定位读数时间：${timing.observedAt?.let(::displayTime) ?: "未记录"}")
                Text("保留上次定位高度 · 天气刷新不会更新", style = MaterialTheme.typography.bodySmall)
            } else {
                Text("${if (timing.batchTimeOnly) "原缓存批次获取" else "获取时间"}：${timing.fetchedAt?.let(::displayTime) ?: "未记录"}")
                Text("观测时间：${timing.observedAt?.let(::displayTime) ?: "未提供，不能由获取时间推断"}")
                Text(if (isCached) "读取状态：本地缓存" else "读取状态：本次获取", style = MaterialTheme.typography.bodySmall)
                if (timing.batchTimeOnly) Text("旧版仅记录整批获取时间；刷新后可记录各来源的获取时间。", style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

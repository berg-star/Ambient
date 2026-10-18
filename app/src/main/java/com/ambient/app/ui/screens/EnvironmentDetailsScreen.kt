package com.ambient.app.ui.screens

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.ambient.app.domain.environment.EnvironmentDetails
import com.ambient.app.ui.components.MetricSourceLabel
import com.ambient.app.ui.overview.EnvironmentUiState
import com.ambient.app.ui.theme.AmbientPanel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

@Composable
fun EnvironmentDetailsScreen(state: EnvironmentUiState, onRefresh: () -> Unit, modifier: Modifier = Modifier) {
    val snapshot = state.snapshot
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    val now by produceState(Instant.now(), lifecycle, snapshot) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (isActive) { value = Instant.now(); delay(60_000) }
        }
    }
    val metrics = remember(snapshot) { EnvironmentDetails.metrics(snapshot) }
    val demo = snapshot?.isDemo ?: !state.preferences.enabled
    val age = snapshot?.let { Duration.between(it.updatedAt, now).seconds }
    val zone = ZoneId.systemDefault()
    val time = snapshot?.updatedAt?.let { DateTimeFormatter.ofPattern("MM-dd HH:mm:ss").withZone(zone).format(it) }
    LazyColumn(modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("ENVIRONMENT DETAILS", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
                    Text(snapshot?.areaLabel ?: state.preferences.area?.label ?: "尚未选择区域", style = MaterialTheme.typography.titleMedium)
                }
                TextButton(onClick = onRefresh, enabled = !state.isRefreshing && !state.isLocating) {
                    if (state.isRefreshing) CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                    else Text(if (demo) "刷新演示" else "刷新天气")
                }
            }
            Text(if (demo) "演示模式 · 这五项未生成示例数值" else "区域 API · 和风天气 · 非手机实测",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary)
            if (time != null) {
                Text("${if (demo) "演示加载" else if (state.isCached) "缓存 · 获取于" else "获取于"} $time",
                    style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("设备时区 ${zone.id} · 获取时间不等于现场测量时间", style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!demo && age != null && age !in 0 until 600) Text("较早数据或设备时间异常，请刷新后参考。",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        listOfNotNull(state.error, state.serviceMessage).distinct().forEach { message -> item {
            Text(message, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        } }
        if (snapshot == null) item {
            Text(if (state.isRefreshing) "正在加载区域天气…" else "暂无区域天气数据，请在设置中启用区域 API 并选择地点。",
                style = MaterialTheme.typography.bodyMedium)
        }
        if (!demo && state.isCached && EnvironmentDetails.ids.any { id -> snapshot?.metrics?.none { it.id == id } == true }) item {
            Text("此缓存未包含部分详情指标，刷新后可尝试获取。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(metrics, key = { it.id }) { metric ->
            AmbientPanel(Modifier.fillMaxWidth(), prominent = metric.id == "feelsLike") {
                Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.SpaceBetween) {
                        Text(metric.label, style = MaterialTheme.typography.titleMedium)
                        MetricSourceLabel(metric)
                    }
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                        Text(metric.value, style = if (metric.id == "feelsLike") MaterialTheme.typography.displayLarge
                            else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Light)
                        Text(metric.unit, Modifier.padding(bottom = 5.dp), style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.primary)
                    }
                    if (metric.value == "—") Text("暂无有效数据", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(metric.description, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
        item {
            Text("与总览使用同一份天气数据。打开详情不额外请求；刷新天气沿用总览的刷新间隔。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (!demo) snapshot?.attributions?.forEach { Text(it, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

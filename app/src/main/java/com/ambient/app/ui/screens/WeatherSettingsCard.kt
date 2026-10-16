package com.ambient.app.ui.screens

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.overview.EnvironmentViewModel
import com.ambient.app.ui.theme.AmbientPanel

@Composable
fun WeatherSettingsCard(overview: EnvironmentViewModel) {
    val state by overview.state.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var confirmClear by remember { mutableStateOf(false) }
    // Keep only temporary URI grants; document IO and key validation run off the main thread.
    var configUri by remember { mutableStateOf<android.net.Uri?>(null) }
    val privatePicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        val metadataUri = configUri
        configUri = null
        if (uri != null && metadataUri != null) overview.importCredentials {
            fun read(selected: android.net.Uri, max: Int) = context.contentResolver.openInputStream(selected)?.use {
                it.readBytesLimited(max).toString(Charsets.UTF_8).trimStart('\uFEFF')
            } ?: error("Cannot open file")
            read(metadataUri, 65536) to read(uri, 16384)
        } else overview.message("导入已取消，原配置保持不变。")
    }
    val metadataPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) { configUri = uri; privatePicker.launch(arrayOf("*/*")) }
        else overview.message("导入已取消，原配置保持不变。")
    }
    AmbientPanel {
        Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                Text("和风天气 QWeather", style = MaterialTheme.typography.titleSmall)
                StatusBadge(if (state.preferences.configured) "已配置" else "未配置")
            }
            Text("先选择 qweather.local.json，再选择 ed25519-private.pem。凭据仅保存在本机，使用系统密钥库加密；不会上传私钥。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            OutlinedButton(onClick = { metadataPicker.launch(arrayOf("*/*")) }, enabled = !state.isRefreshing && !state.isLocating,
                modifier = Modifier.fillMaxWidth()) { Text(if (state.preferences.configured) "重新导入配置与私钥" else "导入配置与私钥") }
            Text("第 1 步选配置 JSON，第 2 步选私钥 PEM。导入后可删除手机“下载”目录中的这两个文件。请保留电脑端备份。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.SpaceBetween) {
                Text("使用真实区域数据", style = MaterialTheme.typography.bodyMedium)
                Switch(checked = state.preferences.enabled, onCheckedChange = overview::setEnabled,
                    enabled = state.preferences.configured && !state.isRefreshing && !state.isLocating)
            }
            Text(if (state.preferences.enabled) "已启用。返回首页选择城市或使用当前位置。"
                else "当前为演示模式，首页数值均有演示标记。", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("前台按需获取，不后台轮询。每次刷新通常调用天气、空气各一次；费用与额度以你的和风控制台为准。",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.serviceMessage?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            if (state.preferences.configured) TextButton(onClick = { confirmClear = true }, enabled = !state.isRefreshing && !state.isLocating) {
                Text("清除本机凭据与天气缓存", color = MaterialTheme.colorScheme.error)
            }
        }
    }
    if (confirmClear) AlertDialog(onDismissRequest = { confirmClear = false }, title = { Text("清除本机配置？") },
        text = { Text("将清除本机保存的凭据、所选区域和天气缓存。和风控制台中的凭据不会被删除。") },
        confirmButton = { TextButton(onClick = { confirmClear = false; overview.clearCredentials() }) { Text("清除") } },
        dismissButton = { TextButton(onClick = { confirmClear = false }) { Text("取消") } })
}

private fun java.io.InputStream.readBytesLimited(limit: Int): ByteArray {
    val buffer = ByteArray(limit + 1)
    var size = 0
    while (size <= limit) {
        val count = read(buffer, size, buffer.size - size)
        if (count <= 0) break
        size += count
    }
    require(size <= limit)
    return buffer.copyOf(size)
}

@Composable
fun WeatherAreaControls(overview: EnvironmentViewModel, onSettings: () -> Unit) {
    val state by overview.state.collectAsStateWithLifecycle()
    var showSearch by remember { mutableStateOf(false) }
    var query by remember { mutableStateOf("") }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { result ->
        if (result.values.any { it }) overview.locate() else overview.message("定位权限未授予，可搜索城市。")
    }
    val enabled = state.preferences.configured && !state.isRefreshing && !state.isLocating
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            TextButton(onClick = if (state.preferences.configured) { { showSearch = true } } else onSettings,
                enabled = !state.isRefreshing && !state.isLocating) { Text(if (state.preferences.configured) "选择城市" else "连接和风天气") }
            TextButton(onClick = { permission.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION)) },
                enabled = enabled) { Text(if (state.isLocating) "定位中…" else "使用当前位置") }
        }
        state.preferences.area?.takeIf { state.preferences.enabled }?.let { area ->
            Text(if (area.fromDevice) "使用上次单次定位区域 · 再次定位请点击上方按钮" else "手动选择的城市区域 · 非手机定位",
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (!showSearch) state.serviceMessage?.let {
            Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (showSearch) AlertDialog(onDismissRequest = { showSearch = false; overview.dismissSearch() },
        title = { Text("选择城市") },
        text = {
            androidx.compose.foundation.lazy.LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                item { OutlinedTextField(query, { query = it.take(80) }, label = { Text("城市 / 区县名称") }, singleLine = true) }
                item { TextButton(onClick = { overview.searchAreas(query) }, enabled = !state.isRefreshing && query.isNotBlank()) { Text(if (state.isRefreshing) "查询中…" else "搜索") } }
                state.serviceMessage?.let { message -> item { Text(message, style = MaterialTheme.typography.bodySmall) } }
                items(state.searchResults.size) { i ->
                    val area = state.searchResults[i]
                    TextButton(onClick = { showSearch = false; overview.selectArea(area) }, enabled = !state.isRefreshing) { Text(area.label) }
                    HorizontalDivider()
                }
            }
        },
        confirmButton = { TextButton(onClick = { showSearch = false; overview.dismissSearch() }) { Text("关闭") } })
}

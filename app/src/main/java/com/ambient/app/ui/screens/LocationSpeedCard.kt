package com.ambient.app.ui.screens

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import android.content.pm.PackageManager
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.location.SpeedStatus
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.location.SpeedViewModel
import com.ambient.app.ui.theme.AmbientPanel
import java.util.Locale

@Composable
fun LocationSpeedCard(viewModel: SpeedViewModel, modifier: Modifier = Modifier) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val enabled by viewModel.enabled.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val virtualDevice = remember { isProbablyEmulator() }
    var showMeaning by remember { mutableStateOf(false) }
    var settingsError by remember { mutableStateOf<String?>(null) }
    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        viewModel.refreshAccess()
    }
    val requestPermission = {
        permissionLauncher.launch(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    val needsPermission = state.status == SpeedStatus.PERMISSION_REQUIRED ||
        state.status == SpeedStatus.PRECISE_PERMISSION_REQUIRED
    val sample = state.sample.takeIf { state.status == SpeedStatus.ACTIVE }
    val colors = MaterialTheme.colorScheme
    AmbientPanel(modifier) {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                    Text("定位速度", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("需精确定位 · 仅在探测页前台使用", style = MaterialTheme.typography.bodySmall,
                        color = colors.onSurfaceVariant)
                }
                Switch(checked = enabled, onCheckedChange = { checked ->
                    viewModel.setEnabled(checked)
                    if (checked && ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                        requestPermission()
                    }
                }, modifier = Modifier.semantics { contentDescription = "定位速度采集" })
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                StatusBadge(if (virtualDevice || sample?.isMock == true) "模拟定位 · 测试数据" else "系统定位 · 速度估计",
                    accent = !virtualDevice && sample?.isMock != true)
                TextButton(onClick = { showMeaning = true }) { Text("测速说明") }
            }
            Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(9.dp)) {
                Text(sample?.let { String.format(Locale.ROOT, "%.1f", it.kilometersPerHour) } ?: "—",
                    fontSize = 40.sp, lineHeight = 48.sp, fontFamily = FontFamily.Monospace,
                    modifier = Modifier.weight(1f, fill = false))
                Text("km/h", Modifier.padding(bottom = 7.dp), style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurfaceVariant)
            }
            if (sample != null) {
                Text(String.format(Locale.ROOT, "%.2f m/s", sample.metersPerSecond),
                    style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
                Text(sample.accuracyMetersPerSecond?.let {
                    String.format(Locale.ROOT, "系统速度精度 ±%.1f km/h（68%% 置信范围）", it * 3.6f)
                } ?: "系统未提供速度精度，读数仅供参考", style = MaterialTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant)
            }
            Text(when (state.status) {
                SpeedStatus.DISABLED -> if (enabled) "定位测速已暂停" else "开启后显示移动速度；不保存或上传位置。"
                SpeedStatus.PERMISSION_REQUIRED -> "需要定位授权，才能读取卫星定位速度。"
                SpeedStatus.PRECISE_PERMISSION_REQUIRED -> "当前仅有大致位置权限，请授权精确定位以使用测速。"
                SpeedStatus.GPS_UNAVAILABLE -> "此设备未提供卫星定位，无法测速。"
                SpeedStatus.GPS_DISABLED -> "系统定位已关闭，请开启后重试。"
                SpeedStatus.WAITING_FOR_FIX -> "等待卫星定位，请到室外开阔处；首次定位可能较慢。"
                SpeedStatus.SPEED_UNAVAILABLE -> "收到定位，但系统尚未提供有效速度。"
                SpeedStatus.ACTIVE -> "实时定位速度 · 超过 10 秒未更新即隐藏"
                SpeedStatus.STALE -> "定位读数已过期，等待新的有效速度。"
                SpeedStatus.ERROR -> state.message ?: "定位暂时不可用，请重试。"
            }, style = MaterialTheme.typography.bodySmall, color = colors.onSurfaceVariant)
            if (needsPermission) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { requestPermission() }) { Text("授权定位") }
                    TextButton(onClick = {
                        runCatching { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                            "package:${context.packageName}".toUri())) }
                            .onFailure { settingsError = "无法打开应用权限，请从系统设置进入 Ambient。" }
                    }) { Text("应用权限") }
                }
            }
            if (state.status == SpeedStatus.GPS_DISABLED) TextButton(onClick = {
                runCatching { context.startActivity(Intent(Settings.ACTION_LOCATION_SOURCE_SETTINGS)) }
                    .onFailure { settingsError = "无法打开定位设置，请手动开启系统定位。" }
            }) { Text("开启系统定位") }
            if (state.status == SpeedStatus.ERROR) TextButton(onClick = viewModel::refreshAccess) { Text("重试") }
            settingsError?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = colors.error) }
        }
    }
    if (showMeaning) AlertDialog(onDismissRequest = { showMeaning = false },
        title = { Text("速度从哪里来") },
        text = { Text("速度来自 Android 系统的卫星定位提供方，不是把加速度累加得到的。显示的是地面移动速率，不代表垂直速度。\n\n室外开阔处通常更可靠；室内、隧道和低速静止时可能无读数或漂移。不会强行把小读数改成零。\n\n精度是系统报告的估计范围，并非误差上限；缺少精度时会明确提示。仅申请使用期间的精确定位，不申请后台定位。\n\n模拟器或系统标记的模拟位置会标为测试数据。") },
        confirmButton = { TextButton(onClick = { showMeaning = false }) { Text("知道了") } })
}

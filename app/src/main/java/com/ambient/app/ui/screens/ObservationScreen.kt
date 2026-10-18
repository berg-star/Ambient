package com.ambient.app.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.observation.*
import com.ambient.app.ui.observation.ObservationViewModel
import com.ambient.app.ui.theme.AmbientPanel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.Locale

@Composable
fun ObservationScreen(model: ObservationViewModel, modifier: Modifier = Modifier) {
    val config by model.config.collectAsStateWithLifecycle()
    val runningFlow = remember(model) { model.state.map { it.status == ObservationStatus.RUNNING }.distinctUntilChanged() }
    val running by runningFlow.collectAsStateWithLifecycle(initialValue = false)
    val virtual = remember { isProbablyEmulator() }
    val keyboard = LocalSoftwareKeyboardController.current
    LifecycleResumeEffect(model) { model.resume(); onPauseOrDispose { model.pause() } }
    LazyColumn(modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(horizontal = 22.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)) {
        item {
            Text("OBSERVATION", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.primary)
            Text(if (virtual) "模拟器 · 虚拟事件，非环境实测" else "手机实测分量 · 统计结果为计算值",
                style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text("主动开始后记录一个传感器。离开此页或进入后台会提前结束，返回后需重新开始。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        item {
            ObservationPanel {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    ObservationKind.entries.forEach { kind ->
                        FilterChip(config.kind == kind, { model.configure(kind = kind) }, { Text(kind.label) },
                            enabled = !running, modifier = Modifier.weight(1f))
                    }
                }
                val capability = model.capabilities[config.kind]
                Text(if (capability?.available == true) "传感器：${capability.name ?: "名称未知"}" else "设备不支持此传感器",
                    style = MaterialTheme.typography.bodySmall)
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    listOf(30,60).forEach { seconds -> FilterChip(config.seconds == seconds,
                        { model.configure(seconds = seconds) }, { Text(if(seconds == 30) "30 秒" else "1 分钟") }, enabled = !running) }
                }
                OutlinedTextField(config.name, { model.configure(name = it) }, enabled = !running, singleLine = true,
                    label = { Text("观察备注（可选）") }, placeholder = { Text("例如：窗边、书桌") }, modifier = Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Button({ keyboard?.hide(); model.start() }, enabled = !running && capability?.available == true) { Text("开始观察") }
                    OutlinedButton(model::stop, enabled = running) { Text("提前结束") }
                }
            }
        }
        item { ObservationReadout(model, virtual) }
        item {
            ObservationPanel {
                Text("如何理解结果", style = MaterialTheme.typography.titleSmall)
                Text("平均值是有效事件的算术平均，不是整段时间平均。光照通常按变化上报，稳定时事件较少，不补造连续读数。",
                    style = MaterialTheme.typography.bodyMedium)
                Text("地磁场 XYZ 是传感器分量；合成强度 √(X²+Y²+Z²) 及其统计均为计算值。气压是手机所在处读数，不是海平面气压。",
                    style = MaterialTheme.typography.bodySmall)
                Text("曲线每 0.5 秒合并，细线显示桶内极值，统计包含全部有效事件。连线仅帮助观察采样点，不代表间隔内连续实测；纵轴自动缩放。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("结果和一份对照仅在本次运行中保留，未写入天气历史；退出应用进程后不恢复。比较时尽量保持姿态和观察条件一致。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun ObservationPanel(content: @Composable ColumnScope.() -> Unit) {
    AmbientPanel(Modifier.fillMaxWidth()) { Column(Modifier.padding(18.dp), verticalArrangement = Arrangement.spacedBy(10.dp), content = content) }
}
private fun observationNumber(value: Double?) = value?.let { String.format(Locale.ROOT, "%.2f", it) } ?: "—"

/** Only this subtree subscribes to the bounded 2 Hz result feed. */
@Composable
private fun ObservationReadout(model: ObservationViewModel, virtual: Boolean) {
    val result by model.state.collectAsStateWithLifecycle()
    val reference by model.reference.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        ObservationPanel {
            val title = when(result.status) {
                ObservationStatus.IDLE -> "尚未开始"; ObservationStatus.RUNNING -> "正在观察"
                ObservationStatus.COMPLETED -> "观察完成"; ObservationStatus.INTERRUPTED -> "已提前结束 · 部分记录"
                ObservationStatus.ERROR -> "未能开始观察"
            }
            Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            if (result.status != ObservationStatus.IDLE) {
                Text("${result.name.ifBlank { result.kind.label }} · ${observationNumber(result.elapsedSeconds)} / ${result.durationSeconds} s",
                    style = MaterialTheme.typography.labelLarge)
                LinearProgressIndicator(progress = { (result.elapsedSeconds / result.durationSeconds).toFloat() }, modifier = Modifier.fillMaxWidth())
                Text("有效事件 ${result.count} · 已忽略 ${result.rejected}", style = MaterialTheme.typography.labelSmall)
                if(result.kind == ObservationKind.LIGHT) Text("光照通常按变化上报，稳定时可能没有新事件。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                result.message?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
            }
            if (result.count == 0L) Text(if(result.status == ObservationStatus.RUNNING) "等待第一笔有效事件…" else "暂无有效读数，不补零。")
            else {
                Text(if(result.kind == ObservationKind.MAGNETIC) "地磁合成强度 · 计算值" else
                    if(virtual) "${result.kind.label} · 虚拟读数" else "${result.kind.label} · 手机实测",
                    style = MaterialTheme.typography.labelMedium)
                Text("${observationNumber(result.bins.lastOrNull()?.value)} ${result.kind.unit}", style = MaterialTheme.typography.headlineLarge)
                if(result.kind == ObservationKind.MAGNETIC) Text("${if(virtual) "虚拟" else "实测"} XYZ：${result.values.joinToString(" / ") { observationNumber(it.toDouble()) }} μT",
                    style = MaterialTheme.typography.bodySmall)
                Text("最低 / 最高 / 事件平均 · 计算统计", style = MaterialTheme.typography.labelLarge)
                Text("${observationNumber(result.minimum)} / ${observationNumber(result.maximum)} / ${observationNumber(result.mean)} ${result.kind.unit}",
                    style = MaterialTheme.typography.titleMedium)
                if(result.count == 1L) Text("仅收到一个有效事件，无法据此判断期间没有变化。", style = MaterialTheme.typography.bodySmall)
                result.eventNanos?.let { Text("最近事件：开始后 ${observationNumber((it - result.startedNanos) / 1_000_000_000.0)} s",
                    style = MaterialTheme.typography.labelSmall) }
                ObservationChart(result)
            }
            if(result.unreliable > 0) Text("${result.unreliable} 个事件被系统标为精度不可靠，统计仍包含这些事件，仅供参考。",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            if(result.canCompare) TextButton(model::keepReference) { Text("设为对照") }
        }
        reference?.let { saved -> ObservationPanel {
            Text("保留的对照 · ${saved.name.ifBlank { saved.kind.label }}", style = MaterialTheme.typography.titleSmall)
            Text("${saved.durationSeconds} s · ${saved.kind.label} · 有效事件 ${saved.count}", style = MaterialTheme.typography.labelSmall)
            Text("最低 ${observationNumber(saved.minimum)} · 最高 ${observationNumber(saved.maximum)} · 平均 ${observationNumber(saved.mean)} ${saved.kind.unit}")
            if(result.canCompare && saved.kind == result.kind && saved.durationSeconds == result.durationSeconds)
                Text("当前平均 − 对照平均：${observationNumber(result.mean!! - saved.mean!!)} ${saved.kind.unit} · 计算差值",
                    style = MaterialTheme.typography.bodySmall)
            else Text("完成同类传感器、相同时长的观察后，可查看平均值差异。", style = MaterialTheme.typography.bodySmall)
            TextButton(model::clearReference) { Text("清除对照") }
        } }
    }
}

@Composable
private fun ObservationChart(result: ObservationResult) {
    val color = MaterialTheme.colorScheme.primary
    val grid = MaterialTheme.colorScheme.outlineVariant
    val min = result.minimum ?: return
    val max = result.maximum ?: return
    val span = (max-min).coerceAtLeast(if(result.kind == ObservationKind.PRESSURE) .1 else 1.0)
    val lower = (min - span*.1).coerceAtLeast(0.0)
    val upper = max + span*.1
    Canvas(Modifier.fillMaxWidth().height(140.dp)) {
        fun x(t: Long) = ((t-result.startedNanos).toDouble() / (result.durationSeconds*1_000_000_000L)*size.width).toFloat()
        fun y(v: Double) = (size.height * (1 - (v-lower)/(upper-lower))).toFloat()
        repeat(3) { drawLine(grid, Offset(0f,size.height*it/2), Offset(size.width,size.height*it/2),1.dp.toPx()) }
        val path = Path()
        result.bins.forEachIndexed { i,b ->
            drawLine(color.copy(alpha=.4f), Offset(x(b.timeNanos), y(b.min)), Offset(x(b.timeNanos),y(b.max)),2.dp.toPx())
            if(i==0) path.moveTo(x(b.timeNanos),y(b.value)) else path.lineTo(x(b.timeNanos),y(b.value))
        }
        drawPath(path,color,style=Stroke(2.dp.toPx()))
        result.bins.lastOrNull()?.let { drawCircle(color,3.dp.toPx(),Offset(x(it.timeNanos),y(it.value))) }
    }
    Text("0 — ${result.durationSeconds} s · 范围 ${observationNumber(lower)} — ${observationNumber(upper)} ${result.kind.unit}",
        style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

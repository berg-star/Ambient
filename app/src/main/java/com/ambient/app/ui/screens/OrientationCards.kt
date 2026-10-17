package com.ambient.app.ui.screens

import android.hardware.SensorManager
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.orientation.OrientationCapability
import com.ambient.app.domain.orientation.OrientationReading
import com.ambient.app.domain.orientation.OrientationSource
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.theme.AmbientPanel
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.roundToInt
import kotlin.math.sin

/** Each instrument owns its subscription; orientation events never invalidate the whole sensor page. */
@Composable
fun CompassCard(capability: OrientationCapability, state: StateFlow<OrientationReading>, enabled: Boolean, virtualDevice: Boolean) {
    val reading by state.collectAsStateWithLifecycle()
    val angles = reading.angles.takeUnless { reading.isStale || reading.error != null }
    val heading = angles?.headingDegrees
    OrientationPanel("手机指南针", capability, reading, enabled, virtualDevice, compass = true) {
        if (!capability.compassAvailable) {
            InstrumentNote("设备未提供完整的磁北方向能力，无法显示指南针。水平仪是否可用会单独判断。")
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.Bottom,
                horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(heading?.let { "${formatHeading(it)}°" } ?: "—",
                    fontSize = 38.sp, lineHeight = 46.sp, fontFamily = FontFamily.Monospace)
                Text(heading?.let(::headingDirection) ?: "磁北方位", Modifier.padding(bottom = 7.dp),
                    style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            }
            CompassDial(heading)
            InstrumentNote("屏幕上边缘朝向 · 0° 北 / 90° 东")
            if (angles != null && heading == null) InstrumentNote("手机接近直立，屏幕上边缘的水平朝向不明确；请将手机放低一些。")
            if (heading != null) when (reading.accuracy) {
                SensorManager.SENSOR_STATUS_UNRELIABLE -> InstrumentNote("系统报告方位精度不可靠，方向仅供参考。磁体、金属或电器可能影响方向；该状态不说明具体原因，也不保证等待后改善。")
                SensorManager.SENSOR_STATUS_ACCURACY_LOW -> InstrumentNote("系统报告方位精度较低，方向仅供参考。可按设备提示校准，并避开明显磁性物体。")
                null -> InstrumentNote("系统尚未报告方位精度，方向仅供参考。")
            }
            reading.headingAccuracyDegrees?.takeIf { heading != null && it.isFinite() && it >= 0f }?.let {
                InstrumentNote(String.format(Locale.ROOT, "系统估计方位误差角 ±%.1f°，并非误差上限", it))
            }
        }
    }
}

@Composable
fun LevelCard(capability: OrientationCapability, state: StateFlow<OrientationReading>, enabled: Boolean, virtualDevice: Boolean) {
    val reading by state.collectAsStateWithLifecycle()
    val angles = reading.angles.takeUnless { reading.isStale || reading.error != null }
    OrientationPanel("倾斜水平仪", capability, reading, enabled, virtualDevice, compass = false) {
        if (!capability.levelAvailable) InstrumentNote("设备未提供姿态或加速度能力，无法计算倾斜角度。")
        else {
            val level = angles?.let { it.faceUp && abs(it.leftRightTiltDegrees) < 1f && abs(it.frontBackTiltDegrees) < 1f } == true
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TiltNumber("左右 · 正值右边抬高", angles?.leftRightTiltDegrees, Modifier.weight(1f))
                TiltNumber("前后 · 正值上边抬高", angles?.frontBackTiltDegrees, Modifier.weight(1f))
            }
            LevelDial(angles?.leftRightTiltDegrees, angles?.frontBackTiltDegrees, angles?.faceUp == true, level)
            InstrumentNote(when {
                angles == null -> "等待有效姿态后显示气泡。"
                !angles.faceUp -> "请将屏幕朝上；翻面时不显示气泡。"
                level -> "接近水平 · 偏离水平均小于 1°"
                else -> "气泡向抬高的一侧移动，中心表示接近水平。"
            })
            InstrumentNote("气泡显示范围 ±30°，以角度数值为准。")
            InstrumentNote("移动或振动会影响倾角；不会在静置后强制归零。")
        }
    }
}

@Composable
private fun OrientationPanel(title: String, capability: OrientationCapability, reading: OrientationReading,
    enabled: Boolean, virtualDevice: Boolean, compass: Boolean, content: @Composable () -> Unit) {
    var meaning by remember { mutableStateOf(false) }
    val available = if (compass) capability.compassAvailable else capability.levelAvailable
    AmbientPanel {
        Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(title, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                TextButton(onClick = { meaning = true }, modifier = Modifier.semantics { contentDescription = "了解$title" }) { Text("了解") }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween) {
                StatusBadge(if (!available) "设备不支持" else if (virtualDevice) "虚拟数据 · 姿态计算"
                    else if (compass) "传感器计算 · 磁北" else "传感器计算 · 倾斜角", accent = available && !virtualDevice)
                Text(when {
                    !available -> "不可用"
                    !enabled -> "已暂停"
                    reading.error != null -> "暂不可用"
                    reading.isStale -> "读数已过期"
                    reading.angles == null -> "等待读数"
                    !reading.isListening -> "已暂停"
                    else -> "采集中"
                }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            content()
            if (available) {
                InstrumentNote(when (capability.source) {
                    OrientationSource.ROTATION_VECTOR -> "Android 旋转向量 · 系统融合"
                    OrientationSource.ACCELEROMETER_MAGNETOMETER -> if (compass) "加速度计 + 磁场 · 算法估计" else "加速度计 · 倾角估计"
                    OrientationSource.ACCELEROMETER_ONLY -> "加速度计 · 倾角估计"
                    OrientationSource.UNAVAILABLE -> "姿态能力不可用"
                })
                if (reading.isStale) InstrumentNote("长时间未收到有效事件，已隐藏仪表读数。")
                else if (reading.angles != null && (!enabled || !reading.isListening)) InstrumentNote("保留最后一次计算结果，当前未采集。")
                reading.error?.let { InstrumentNote(it) }
            }
        }
    }
    if (meaning) OrientationMeaningDialog(title, capability, reading, virtualDevice, compass) { meaning = false }
}

@Composable
private fun CompassDial(heading: Float?) {
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(212.dp).semantics {
            contentDescription = heading?.let { "磁北方位 ${formatHeading(it)} 度，${headingDirection(it)}" } ?: "指南针尚无有效方向"
        }) {
            val r = size.minDimension * .43f
            val c = center
            drawCircle(colors.onSurface.copy(alpha = .025f), r, c)
            drawCircle(colors.outlineVariant.copy(alpha = .6f), r, c, style = Stroke(1.dp.toPx()))
            fun point(bearing: Float, radius: Float): Offset {
                val rad = Math.toRadians((bearing - (heading ?: 0f) - 90f).toDouble())
                return c + Offset(cos(rad).toFloat(), sin(rad).toFloat()) * radius
            }
            for (degree in 0 until 360 step 15) {
                val major = degree % 90 == 0
                drawLine(if (major) colors.onSurfaceVariant else colors.outlineVariant,
                    point(degree.toFloat(), r * if (major) .84f else .91f), point(degree.toFloat(), r * .97f),
                    if (major) 2.dp.toPx() else 1.dp.toPx(), cap = StrokeCap.Round)
            }
            if (heading != null) {
                listOf("北", "东", "南", "西").forEachIndexed { i, text ->
                    val label = measurer.measure(text, TextStyle(color = if (i == 0) colors.primary else colors.onSurfaceVariant,
                        fontSize = 13.sp, fontWeight = if (i == 0) FontWeight.Bold else FontWeight.Normal))
                    val p = point(i * 90f, r * .67f)
                    drawText(label, topLeft = p - Offset(label.size.width / 2f, label.size.height / 2f))
                }
                val tip = point(0f, r * .48f)
                val left = point(270f, r * .085f)
                val right = point(90f, r * .085f)
                drawPath(Path().apply { moveTo(tip.x, tip.y); lineTo(left.x, left.y); lineTo(right.x, right.y); close() }, colors.primary)
                drawLine(colors.onSurfaceVariant.copy(alpha = .55f), c, point(180f, r * .38f), 3.dp.toPx(), StrokeCap.Round)
                drawCircle(colors.primary, 4.dp.toPx(), c)
                drawPath(Path().apply {
                    moveTo(c.x, c.y - r - 2.dp.toPx())
                    lineTo(c.x - 5.dp.toPx(), c.y - r - 10.dp.toPx())
                    lineTo(c.x + 5.dp.toPx(), c.y - r - 10.dp.toPx()); close()
                }, colors.primary)
            }
        }
    }
}

@Composable
private fun LevelDial(leftRight: Float?, frontBack: Float?, faceUp: Boolean, level: Boolean) {
    val colors = MaterialTheme.colorScheme
    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(190.dp).semantics { contentDescription = when {
            leftRight == null || frontBack == null -> "水平仪尚无有效姿态"
            !faceUp -> "屏幕朝下，气泡隐藏"
            level -> "气泡居中，接近水平"
            else -> "水平仪气泡，正值对应屏幕右边和上边抬高"
        } }) {
            val r = size.minDimension * .43f
            drawCircle(colors.onSurface.copy(alpha = .025f), r)
            drawCircle(colors.outlineVariant.copy(alpha = .65f), r, style = Stroke(1.dp.toPx()))
            drawCircle(colors.outlineVariant.copy(alpha = .6f), r / 2, style = Stroke(1.dp.toPx()))
            drawLine(colors.outlineVariant.copy(alpha = .55f), center - Offset(r, 0f), center + Offset(r, 0f), 1.dp.toPx())
            drawLine(colors.outlineVariant.copy(alpha = .55f), center - Offset(0f, r), center + Offset(0f, r), 1.dp.toPx())
            drawCircle(colors.primary.copy(alpha = .45f), 7.dp.toPx(), style = Stroke(1.dp.toPx()))
            if (faceUp && leftRight != null && frontBack != null) {
                val x = leftRight / 30f
                val y = -frontBack / 30f
                val scale = hypot(x, y).coerceAtLeast(1f)
                val bubble = center + Offset(x / scale, y / scale) * (r - 12.dp.toPx())
                drawCircle(colors.primary.copy(alpha = if (level) .3f else .16f), 12.dp.toPx(), bubble)
                drawCircle(colors.primary, 12.dp.toPx(), bubble, style = Stroke(1.5.dp.toPx()))
                drawCircle(colors.primary, 2.dp.toPx(), bubble)
            }
        }
    }
}

@Composable
private fun TiltNumber(label: String, value: Float?, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(value?.let {
            val rounded = (it * 10).roundToInt() / 10f
            String.format(Locale.ROOT, "%+.1f°", if (rounded == 0f) 0f else rounded)
        } ?: "—",
            fontSize = 28.sp, lineHeight = 36.sp, fontFamily = FontFamily.Monospace)
        InstrumentNote(label)
    }
}

@Composable
private fun InstrumentNote(text: String) = Text(text, style = MaterialTheme.typography.bodySmall,
    color = MaterialTheme.colorScheme.onSurfaceVariant)

private fun headingDirection(heading: Float): String =
    listOf("北", "东北", "东", "东南", "南", "西南", "西", "西北")[((heading + 22.5f) / 45f).toInt() % 8]

private fun formatHeading(heading: Float): String =
    String.format(Locale.ROOT, "%.1f", (heading * 10).roundToInt().mod(3600) / 10f)

@Composable
private fun OrientationMeaningDialog(title: String, capability: OrientationCapability, reading: OrientationReading,
    virtualDevice: Boolean, compass: Boolean, onDismiss: () -> Unit) {
    // Freeze diagnostic metadata when opening, so text stays readable during sampling.
    val snapshot = remember { reading }
    AlertDialog(onDismissRequest = onDismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if (virtualDevice) Text("当前为模拟器虚拟传感器事件，仅用于验证功能，不能判断真实方向或水平状态。")
            Text(if (compass) "方位是当前屏幕上边缘在水平面上的方向，以磁北为 0°，顺时针东为 90°。磁北与地图常用的真北不同，本页未做磁偏角修正。它表示手机朝向，不表示风从哪里吹来。"
                else "左右正值表示屏幕右边缘抬高，前后正值表示屏幕上边缘抬高；会随横竖屏重新映射。屏幕朝下时隐藏气泡。两轴倾角的绝对值都小于 1°且屏幕朝上才提示接近水平，不能代替经过校准的专业水平仪。")
            Text(if (compass) "优先使用 Android 旋转向量的系统融合结果；设备缺少该能力时，尝试加速度计与磁场计算。磁性配件、金属、电器和移动都可能影响读数。精度状态是设备报告，不代表软件发生故障；未知精度不会当作高精度。"
                else "倾角由姿态或包含重力的加速度计算，移动和振动会影响结果。气泡显示范围为 ±30°，超出范围停留边缘，具体角度以数值为准。Ambient 不把静置偏差强制改为零。")
            Text("旋转向量：${capability.rotationVectorName ?: "未提供"}\n加速度计：${capability.accelerometerName ?: "未提供"}\n磁场传感器：${capability.magneticFieldName ?: "未提供"}")
            Text("以下为打开说明时的状态，不持续刷新。", style = MaterialTheme.typography.bodySmall)
            Text(buildString {
                appendLine("计算来源：${when (capability.source) {
                    OrientationSource.ROTATION_VECTOR -> "系统姿态融合"
                    OrientationSource.ACCELEROMETER_MAGNETOMETER -> "加速度与磁场估计"
                    OrientationSource.ACCELEROMETER_ONLY -> "加速度倾角估计"
                    OrientationSource.UNAVAILABLE -> "未提供"
                }}")
                appendLine("事件时间：${snapshot.timestampNanos?.let { String.format(Locale.ROOT, "%.3f s", it / 1_000_000_000.0) } ?: "尚无"}（设备启动后）")
                if (compass) appendLine("系统精度状态：${when (snapshot.accuracy) {
                    null -> "未报告"
                    SensorManager.SENSOR_STATUS_UNRELIABLE -> "设备报告不可靠"
                    SensorManager.SENSOR_STATUS_ACCURACY_LOW -> "低"
                    SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM -> "中"
                    SensorManager.SENSOR_STATUS_ACCURACY_HIGH -> "高"
                    else -> "未知"
                }}")
                append("采集状态：${if (snapshot.isStale) "已过期" else if (snapshot.isListening) "采集中" else "未采集"}")
            }, style = MaterialTheme.typography.bodySmall)
        }
    }, confirmButton = { TextButton(onClick = onDismiss) { Text("知道了") } })
}

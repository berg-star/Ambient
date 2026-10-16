package com.ambient.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.app.domain.environment.EnvironmentMetric
import kotlin.math.cos
import kotlin.math.sin

/** Shared by connected rows and both compact card layouts. No phone sensor subscription. */
@Composable
fun WindDirectionIndicator(metric: EnvironmentMetric, modifier: Modifier = Modifier, compact: Boolean = false) {
    val bearing = metric.windFromDegrees?.takeIf { it.isFinite() && it in 0.0..359.0 }
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(metric.label, style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(when {
                bearing != null -> "来自"
                metric.value == "不定" || metric.value == "无风向" -> "无明确来向"
                metric.value != "—" -> "刷新以显示箭头"
                else -> "暂无来向"
            }, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(metric.value, fontSize = if (compact) 20.sp else 25.sp, lineHeight = 29.sp)
            MetricSourceLabel(metric)
        }
        WindCompass(bearing, metric.value, Modifier.size(if (compact) 52.dp else 68.dp))
    }
}

/** North stays at the top. An upwind dot plus an inward arrow depict incoming air, not phone heading. */
@Composable
fun WindCompass(fromDegrees: Double?, directionLabel: String, modifier: Modifier = Modifier) {
    val color = MaterialTheme.colorScheme.primary
    val muted = MaterialTheme.colorScheme.onSurfaceVariant
    val bearing = fromDegrees?.takeIf { it.isFinite() && it in 0.0..359.0 }
    Box(modifier.clearAndSetSemantics {
        contentDescription = if (bearing != null) "风从${directionLabel}吹来；上北下南，亮点为来向，箭头朝中心吹入"
            else "风向罗盘，上北下南；暂无确定来向，不显示箭头"
    }) {
        Canvas(Modifier.matchParentSize()) {
            val s = size.minDimension
            val center = Offset(size.width / 2f, size.height / 2f)
            val radius = s * .30f
            drawCircle(muted.copy(alpha = .30f), radius, center, style = Stroke(.8.dp.toPx()))
            for (index in 0 until 16) {
                val radians = Math.toRadians(index * 22.5)
                val unit = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
                val inner = if (index % 4 == 0) .78f else .89f
                drawLine(muted.copy(alpha = .45f), center + unit * (radius * inner), center + unit * radius,
                    .8.dp.toPx(), StrokeCap.Round)
            }
            if (bearing != null) {
                val radians = Math.toRadians(bearing)
                val from = Offset(sin(radians).toFloat(), -cos(radians).toFloat())
                val sideways = Offset(-from.y, from.x)
                val start = center + from * radius
                val tip = center - from * (s * .075f)
                val back = tip + from * (s * .12f)
                drawLine(color, start, back, 1.8.dp.toPx(), StrokeCap.Round)
                drawPath(Path().apply {
                    moveTo(tip.x, tip.y)
                    val left = back + sideways * (s * .055f)
                    val right = back - sideways * (s * .055f)
                    lineTo(left.x, left.y); lineTo(right.x, right.y); close()
                }, color)
                drawCircle(color, s * .028f, start)
            } else {
                drawLine(muted.copy(alpha = .55f), center - Offset(s * .055f, 0f), center + Offset(s * .055f, 0f),
                    1.2.dp.toPx(), StrokeCap.Round)
            }
        }
        Text("北", Modifier.align(Alignment.TopCenter), fontSize = 9.sp, lineHeight = 10.sp, color = color)
        Text("东", Modifier.align(Alignment.CenterEnd), fontSize = 9.sp, lineHeight = 10.sp, color = muted)
        Text("南", Modifier.align(Alignment.BottomCenter), fontSize = 9.sp, lineHeight = 10.sp, color = muted)
        Text("西", Modifier.align(Alignment.CenterStart), fontSize = 9.sp, lineHeight = 10.sp, color = muted)
    }
}

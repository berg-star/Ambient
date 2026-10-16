package com.ambient.app.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.ui.theme.LocalAmbientVisuals

@Composable
fun PageHeading(eyebrow: String, title: String, subtitle: String, modifier: Modifier = Modifier) {
    val visuals = LocalAmbientVisuals.current
    Column(modifier = modifier.fillMaxWidth()) {
        Text(eyebrow, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.primary,
            letterSpacing = 2.sp)
        Spacer(Modifier.height(9.dp))
        Text(title, style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Medium,
            fontFamily = if (visuals.serifNumbers) FontFamily.Serif else FontFamily.Default)
        Spacer(Modifier.height(8.dp))
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Compact source text stays visible even in the smallest overview tile. */
@Composable
fun MetricSourceLabel(metric: EnvironmentMetric, modifier: Modifier = Modifier) {
    val noData = metric.value == "—" || metric.value == "--"
    val label = if (noData) "尚无数据" else when (metric.source) {
        DataSource.DEMO -> "演示数据"
        DataSource.DEVICE -> "手机实测"
        DataSource.REGIONAL_API -> "区域 API"
        DataSource.ESTIMATED -> "算法估计"
    }
    Text(label, modifier = modifier, fontSize = 11.sp, lineHeight = 16.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, softWrap = false)
}

@Composable
fun SourceBadge(source: DataSource, modifier: Modifier = Modifier) {
    StatusBadge(
        text = when (source) {
            DataSource.DEMO -> "演示数据"
            DataSource.DEVICE -> "手机实测"
            DataSource.REGIONAL_API -> "区域 API"
            DataSource.ESTIMATED -> "算法估计"
        },
        modifier = modifier,
        accent = source == DataSource.DEVICE,
    )
}

@Composable
fun StatusBadge(text: String, modifier: Modifier = Modifier, accent: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    Surface(
        modifier = modifier,
        shape = RoundedCornerShape(7.dp),
        color = if (accent) colors.primary.copy(alpha = .12f) else colors.onSurface.copy(alpha = .07f),
        contentColor = if (accent) colors.primary else colors.onSurfaceVariant,
    ) {
        Text(text, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
            style = MaterialTheme.typography.labelSmall, fontWeight = FontWeight.Medium)
    }
}

@Composable
fun SectionLabel(title: String, modifier: Modifier = Modifier, detail: String? = null) {
    Row(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        if (detail != null) Text(detail, style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Small, original line symbols share one stroke weight throughout the app. */
@Composable
fun InstrumentSymbol(kind: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.primary) {
    Canvas(modifier.size(28.dp)) {
        val s = size.minDimension
        val stroke = s * .055f
        val center = Offset(s / 2f, s / 2f)
        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color, Offset(x1 * s, y1 * s), Offset(x2 * s, y2 * s), stroke, StrokeCap.Round)
        when (kind) {
            "temperature" -> {
                drawCircle(color, s * .13f, Offset(s * .5f, s * .76f), style = Stroke(stroke))
                drawRoundRect(color, Offset(s * .42f, s * .12f), Size(s * .16f, s * .54f),
                    cornerRadius = androidx.compose.ui.geometry.CornerRadius(s * .08f), style = Stroke(stroke))
                line(.5f, .4f, .5f, .75f)
            }
            "humidity" -> {
                val p = Path().apply {
                    moveTo(s * .5f, s * .1f)
                    cubicTo(s * .42f, s * .3f, s * .2f, s * .5f, s * .2f, s * .64f)
                    cubicTo(s * .2f, s * 1f, s * .8f, s * 1f, s * .8f, s * .64f)
                    cubicTo(s * .8f, s * .5f, s * .58f, s * .3f, s * .5f, s * .1f)
                }
                drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(.38f, .68f, .45f, .77f)
            }
            "altitude" -> {
                val p = Path().apply {
                    moveTo(s * .08f, s * .8f); lineTo(s * .42f, s * .18f)
                    lineTo(s * .64f, s * .6f); lineTo(s * .76f, s * .4f); lineTo(s * .94f, s * .8f)
                }
                drawPath(p, color, style = Stroke(stroke, cap = StrokeCap.Round))
                line(.32f, .38f, .43f, .47f); line(.43f, .47f, .52f, .36f)
            }
            "pressure", "PRESSURE" -> {
                drawArc(color, 145f, 250f, false, Offset(s * .13f, s * .13f),
                    Size(s * .74f, s * .74f), style = Stroke(stroke, cap = StrokeCap.Round))
                line(.5f, .52f, .72f, .3f)
                drawCircle(color, s * .065f, center)
                line(.36f, .85f, .64f, .85f)
            }
            "windSpeed", "windDirection", "GYROSCOPE" -> {
                line(.08f, .33f, .73f, .33f); line(.18f, .53f, .9f, .53f)
                line(.08f, .73f, .61f, .73f)
                drawArc(color, 180f, 230f, false, Offset(s * .62f, s * .12f), Size(s * .22f, s * .22f),
                    style = Stroke(stroke, cap = StrokeCap.Round))
            }
            "LIGHT", "uv" -> {
                drawCircle(color, s * .18f, center, style = Stroke(stroke))
                for (i in 0..7) {
                    val a = i * Math.PI / 4
                    val d = Offset(kotlin.math.cos(a).toFloat(), kotlin.math.sin(a).toFloat())
                    drawLine(color, center + d * (s * .31f), center + d * (s * .43f), stroke, StrokeCap.Round)
                }
            }
            "MAGNETIC_FIELD" -> {
                drawArc(color, 0f, 180f, false, Offset(s * .2f, s * .36f), Size(s * .6f, s * .5f),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                line(.2f, .6f, .2f, .18f); line(.8f, .6f, .8f, .18f)
                line(.2f, .32f, .33f, .32f); line(.67f, .32f, .8f, .32f)
            }
            "ACCELEROMETER", "LINEAR_ACCELERATION" -> {
                line(.5f, .85f, .5f, .15f); line(.5f, .85f, .9f, .62f)
                line(.5f, .85f, .1f, .62f); line(.5f, .15f, .4f, .28f)
                line(.5f, .15f, .6f, .28f)
            }
            "history" -> {
                drawArc(color, -70f, 300f, false, Offset(s * .12f, s * .12f), Size(s * .76f, s * .76f),
                    style = Stroke(stroke, cap = StrokeCap.Round))
                line(.5f, .3f, .5f, .52f); line(.5f, .52f, .68f, .61f)
            }
            else -> {
                for (i in 0..2) {
                    val y = .28f + i * .22f
                    line(.14f, y, .86f, y)
                    drawCircle(color, s * .04f, Offset(s * (.25f + i * .23f), s * y))
                }
            }
        }
    }
}

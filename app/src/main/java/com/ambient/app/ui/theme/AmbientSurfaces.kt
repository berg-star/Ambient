package com.ambient.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/** Static brushes are cached at the measured size; there are no animation frames or blur layers. */
@Composable
fun AmbientBackdrop(
    modifier: Modifier = Modifier,
    content: @Composable BoxScope.() -> Unit,
) {
    val visuals = LocalAmbientVisuals.current
    Box(
        modifier = modifier.fillMaxSize().drawWithCache {
            val background = Brush.linearGradient(
                colors = visuals.backgroundColors,
                start = Offset.Zero,
                end = Offset(size.width * .65f, size.height),
            )
            val cornerLight = Brush.radialGradient(
                colors = listOf(
                    visuals.highlightColor.copy(alpha = if (visuals.isDark) .14f else .16f),
                    Color.Transparent,
                ),
                center = Offset(size.width * 1.05f, size.height * .06f),
                radius = size.maxDimension * .65f,
            )
            onDrawBehind {
                drawRect(background)
                drawRect(cornerLight)
            }
        },
        content = content,
    )
}

/** Opaque gradient stops create the glass appearance while preserving predictable readability. */
@Composable
fun AmbientPanel(
    modifier: Modifier = Modifier,
    prominent: Boolean = false,
    content: @Composable () -> Unit,
) {
    val visuals = LocalAmbientVisuals.current
    val shape = RoundedCornerShape(visuals.radius)
    val stops = if (prominent) visuals.heroColors else visuals.cardColors
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(stops))
            .border(1.dp, visuals.borderColor, shape),
    ) {
        content()
    }
}

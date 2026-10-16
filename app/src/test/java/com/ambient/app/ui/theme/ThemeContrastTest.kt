package com.ambient.app.ui.theme

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

class ThemeContrastTest {
    @Test
    fun `all themes keep body text and source labels readable on the complete gradients`() {
        ThemeStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val visuals = ambientVisualsFor(style, dark)
                val scheme = ambientColorSchemeFor(style, dark)
                val panelStops = visuals.cardColors + visuals.heroColors
                panelStops.forEach { assertEquals("Panel stops must be opaque", 1f, it.alpha) }
                val backdrop = interpolated(visuals.backgroundColors).flatMap { color ->
                    // Include the brightest point of the radial corner highlight as well as its fade.
                    listOf(color, visuals.highlightColor.copy(alpha = if (dark) .14f else .16f).compositeOver(color))
                }
                val surfaces = backdrop + interpolated(visuals.cardColors) + interpolated(visuals.heroColors)
                surfaces.forEach { background ->
                    assertContrast(style, dark, "正文", scheme.onSurface, background)
                    assertContrast(style, dark, "来源与单位小字", scheme.onSurfaceVariant, background)
                    assertContrast(style, dark, "强调文字", scheme.primary, background)
                }
            }
        }
    }

    @Test
    fun `material action and container labels meet normal text contrast`() {
        ThemeStyle.entries.forEach { style ->
            listOf(false, true).forEach { dark ->
                val scheme = ambientColorSchemeFor(style, dark)
                listOf(
                    scheme.onPrimary to scheme.primary,
                    scheme.onPrimaryContainer to scheme.primaryContainer,
                    scheme.onSecondary to scheme.secondary,
                    scheme.onSecondaryContainer to scheme.secondaryContainer,
                    scheme.onTertiary to scheme.tertiary,
                    scheme.onTertiaryContainer to scheme.tertiaryContainer,
                ).forEach { (text, surface) -> assertContrast(style, dark, "控件文字", text, surface) }
            }
        }
    }

    @Test
    fun `theme modes follow explicit choice or system appearance`() {
        assertTrue(ThemeMode.DARK.isDark(false))
        assertTrue(!ThemeMode.LIGHT.isDark(true))
        assertTrue(ThemeMode.SYSTEM.isDark(true))
        assertTrue(!ThemeMode.SYSTEM.isDark(false))
        assertEquals(ThemeMode.LIGHT, ThemeStyle.SUNLIT_AMBER.preferredMode)
        assertEquals(ThemeMode.LIGHT, ThemeStyle.MISTY_DUSK.preferredMode)
        assertEquals(ThemeMode.DARK, ThemeStyle.FLOWING_GLASS.preferredMode)
    }

    private fun interpolated(stops: List<Color>) = stops.zipWithNext().flatMap { (first, second) ->
        (0..10).map { step -> lerp(first, second, step / 10f) }
    }

    private fun assertContrast(style: ThemeStyle, dark: Boolean, purpose: String, text: Color, background: Color) {
        val first = luminance(text)
        val second = luminance(background)
        val contrast = (max(first, second) + .05) / (min(first, second) + .05)
        assertTrue("$style dark=$dark $purpose contrast=$contrast (< 4.5)", contrast >= 4.5)
    }

    private fun luminance(color: Color): Double {
        fun linear(channel: Float): Double {
            val value = channel.toDouble()
            return if (value <= .04045) value / 12.92 else ((value + .055) / 1.055).pow(2.4)
        }
        return .2126 * linear(color.red) + .7152 * linear(color.green) + .0722 * linear(color.blue)
    }
}

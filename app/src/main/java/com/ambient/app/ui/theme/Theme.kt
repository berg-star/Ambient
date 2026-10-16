package com.ambient.app.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

enum class ThemeMode { DARK, LIGHT, SYSTEM }

fun ThemeMode.isDark(systemDark: Boolean) = this == ThemeMode.DARK || this == ThemeMode.SYSTEM && systemDark

enum class ThemeStyle(
    val displayName: String,
    val description: String,
    val preferredMode: ThemeMode,
) {
    SUNLIT_AMBER("日光琥珀", "奶油白与浅杏渐变，温润的纸感数字", ThemeMode.LIGHT),
    MISTY_DUSK("雾蓝暮色", "雾蓝与粉杏交融，清晰轻盈的白色卡片", ThemeMode.LIGHT),
    FLOWING_GLASS("流光玻璃", "深靛与烟紫渐变，静谧的玻璃层次", ThemeMode.DARK),
}

@Immutable
data class AmbientVisuals(
    val style: ThemeStyle,
    val isDark: Boolean,
    val backgroundColors: List<Color>,
    val highlightColor: Color,
    val cardColors: List<Color>,
    val heroColors: List<Color>,
    val borderColor: Color,
    val radius: Dp,
    val serifNumbers: Boolean,
    val unframedTemperature: Boolean,
)

val LocalAmbientVisuals = staticCompositionLocalOf {
    ambientVisualsFor(ThemeStyle.FLOWING_GLASS, dark = true)
}

/** Every panel stop is opaque so the same text contrast is retained over the backdrop. */
fun ambientVisualsFor(style: ThemeStyle, dark: Boolean): AmbientVisuals = when (style) {
    ThemeStyle.SUNLIT_AMBER -> AmbientVisuals(
        style = style,
        isDark = dark,
        backgroundColors = if (dark) colors(0xFF211B17, 0xFF30231D, 0xFF1D1A18)
            else colors(0xFFFCF7EE, 0xFFF7E5D4, 0xFFF2E5D3),
        highlightColor = if (dark) Color(0xFFBB865A) else Color(0xFFE8A368),
        cardColors = if (dark) colors(0xFF302923, 0xFF27221F)
            else colors(0xFFFFFCF7, 0xFFF7EFE4),
        heroColors = if (dark) colors(0xFF3A2D23, 0xFF2E2520)
            else colors(0xFFFAF0E2, 0xFFF4E3D0),
        borderColor = if (dark) Color(0xFF695344) else Color(0xFFE0CCB4),
        radius = 20.dp,
        serifNumbers = true,
        unframedTemperature = true,
    )
    ThemeStyle.MISTY_DUSK -> AmbientVisuals(
        style = style,
        isDark = dark,
        backgroundColors = if (dark) colors(0xFF171F2E, 0xFF262637, 0xFF211D2B)
            else colors(0xFFEDF3F8, 0xFFE9EDF4, 0xFFF3E6DF),
        highlightColor = if (dark) Color(0xFF8EA6CB) else Color(0xFF8BAFDA),
        cardColors = if (dark) colors(0xFF253044, 0xFF2B2C40)
            else colors(0xFFFDFDFD, 0xFFF8FAFC),
        heroColors = if (dark) colors(0xFF2F3C51, 0xFF373343)
            else colors(0xFFF0F4FA, 0xFFF8EDE7),
        borderColor = if (dark) Color(0xFF55647D) else Color(0xFFD4DEE9),
        radius = 18.dp,
        serifNumbers = false,
        unframedTemperature = true,
    )
    ThemeStyle.FLOWING_GLASS -> AmbientVisuals(
        style = style,
        isDark = dark,
        backgroundColors = if (dark) colors(0xFF1E263A, 0xFF26283D, 0xFF171E30)
            else colors(0xFFEEF0F7, 0xFFE8E7F2, 0xFFE8E2E5),
        highlightColor = if (dark) Color(0xFF8B7DAF) else Color(0xFFA49DCA),
        cardColors = if (dark) colors(0xFF373B51, 0xFF28344A)
            else colors(0xFFFBFAFD, 0xFFEFF1F8),
        heroColors = if (dark) colors(0xFF41445F, 0xFF2D3A50)
            else colors(0xFFECEBF5, 0xFFE4EAF4),
        borderColor = if (dark) Color(0xFF73798D) else Color(0xFFC8C9DA),
        radius = 22.dp,
        serifNumbers = false,
        unframedTemperature = false,
    )
}

/** Public pure palette lookup also makes the six appearances verifiable without a device. */
fun ambientColorSchemeFor(style: ThemeStyle, dark: Boolean): ColorScheme {
    val visuals = ambientVisualsFor(style, dark)
    val foreground: Color
    val muted: Color
    val accent: Color
    val container: Color
    val tertiary: Color
    if (dark) {
        foreground = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFFF8EFE5)
            ThemeStyle.MISTY_DUSK -> Color(0xFFEAF0F7)
            ThemeStyle.FLOWING_GLASS -> Color(0xFFF4F1ED)
        }
        muted = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFFD1C0AE)
            ThemeStyle.MISTY_DUSK -> Color(0xFFBDC9D8)
            ThemeStyle.FLOWING_GLASS -> Color(0xFFC4C6D2)
        }
        accent = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFFE9B882)
            ThemeStyle.MISTY_DUSK -> Color(0xFFB3CDE8)
            ThemeStyle.FLOWING_GLASS -> Color(0xFFE3C29E)
        }
        container = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFF4B3829)
            ThemeStyle.MISTY_DUSK -> Color(0xFF35455E)
            ThemeStyle.FLOWING_GLASS -> Color(0xFF464052)
        }
        tertiary = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFFBFCDB0)
            ThemeStyle.MISTY_DUSK -> Color(0xFFE4BFB4)
            ThemeStyle.FLOWING_GLASS -> Color(0xFFBDCBEE)
        }
    } else {
        foreground = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFF342E27)
            ThemeStyle.MISTY_DUSK -> Color(0xFF293849)
            ThemeStyle.FLOWING_GLASS -> Color(0xFF262B3F)
        }
        muted = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFF715C4A)
            ThemeStyle.MISTY_DUSK -> Color(0xFF505E71)
            ThemeStyle.FLOWING_GLASS -> Color(0xFF575E73)
        }
        accent = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFF8C4C22)
            ThemeStyle.MISTY_DUSK -> Color(0xFF3B6188)
            ThemeStyle.FLOWING_GLASS -> Color(0xFF77542F)
        }
        container = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFFEBD9BF)
            ThemeStyle.MISTY_DUSK -> Color(0xFFDCE7F4)
            ThemeStyle.FLOWING_GLASS -> Color(0xFFE0DBED)
        }
        tertiary = when (style) {
            ThemeStyle.SUNLIT_AMBER -> Color(0xFF4D6241)
            ThemeStyle.MISTY_DUSK -> Color(0xFF805249)
            ThemeStyle.FLOWING_GLASS -> Color(0xFF505F8F)
        }
    }
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = accent,
        onPrimary = if (dark) Color(0xFF221F24) else Color.White,
        primaryContainer = container,
        onPrimaryContainer = foreground,
        secondary = muted,
        onSecondary = if (dark) Color(0xFF221F24) else Color.White,
        secondaryContainer = container,
        onSecondaryContainer = foreground,
        tertiary = tertiary,
        onTertiary = if (dark) Color(0xFF221F24) else Color.White,
        tertiaryContainer = container,
        onTertiaryContainer = foreground,
        background = visuals.backgroundColors.first(),
        onBackground = foreground,
        surface = visuals.cardColors.first(),
        onSurface = foreground,
        surfaceVariant = visuals.cardColors.last(),
        onSurfaceVariant = muted,
        surfaceContainerLowest = visuals.backgroundColors.last(),
        surfaceContainerLow = visuals.cardColors.last(),
        surfaceContainer = visuals.cardColors.first(),
        surfaceContainerHigh = visuals.heroColors.last(),
        surfaceContainerHighest = visuals.heroColors.first(),
        surfaceBright = visuals.cardColors.first(),
        surfaceDim = visuals.backgroundColors.last(),
        outline = muted,
        outlineVariant = visuals.borderColor,
        surfaceTint = accent,
        inverseSurface = if (dark) Color(0xFFF1EFF4) else Color(0xFF292B36),
        inverseOnSurface = if (dark) Color(0xFF292B36) else Color(0xFFF1EFF4),
        inversePrimary = if (dark) Color(0xFF77542F) else Color(0xFFE3C29E),
    )
}

@Composable
fun AmbientTheme(
    mode: ThemeMode,
    style: ThemeStyle = ThemeStyle.FLOWING_GLASS,
    content: @Composable () -> Unit,
) {
    val dark = mode.isDark(isSystemInDarkTheme())
    val visuals = ambientVisualsFor(style, dark)
    val typography = Typography().let { base ->
        if (visuals.serifNumbers) base.copy(
            displayLarge = base.displayLarge.copy(fontFamily = FontFamily.Serif),
            displayMedium = base.displayMedium.copy(fontFamily = FontFamily.Serif),
            displaySmall = base.displaySmall.copy(fontFamily = FontFamily.Serif),
        ) else base
    }
    CompositionLocalProvider(LocalAmbientVisuals provides visuals) {
        MaterialTheme(
            colorScheme = ambientColorSchemeFor(style, dark),
            typography = typography,
            content = content,
        )
    }
}

private fun colors(vararg values: Long) = values.map { Color(it) }

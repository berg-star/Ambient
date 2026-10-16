package com.ambient.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambient.app.ui.components.SectionLabel
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.theme.AmbientPanel
import com.ambient.app.ui.theme.LocalAmbientVisuals
import com.ambient.app.ui.theme.ThemeMode
import com.ambient.app.ui.theme.ThemeStyle

@Composable
fun SettingsScreen(
    themeMode: ThemeMode,
    onThemeModeChange: (ThemeMode) -> Unit,
    themeStyle: ThemeStyle,
    onThemeStyleChange: (ThemeStyle) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Text("偏好设置", style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.SemiBold)
        SectionLabel("主题风格", detail = "随心切换")
        Column(Modifier.fillMaxWidth().selectableGroup(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            ThemeStyle.entries.forEach { style ->
                ThemeStyleOption(style, themeStyle == style) { onThemeStyleChange(style) }
            }
        }
        Text("切换主题会应用推荐明暗外观，可在下方调整。",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        SectionLabel("显示外观")
        Row(Modifier.fillMaxWidth().selectableGroup(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(ThemeMode.DARK to "深色", ThemeMode.LIGHT to "浅色", ThemeMode.SYSTEM to "跟随系统")
                .forEach { (mode, title) ->
                    val selected = themeMode == mode
                    val shape = RoundedCornerShape(14.dp)
                    Box(
                        Modifier.weight(1f).heightIn(min = 48.dp).clip(shape)
                            .background(if (selected) MaterialTheme.colorScheme.primaryContainer
                                else MaterialTheme.colorScheme.surfaceContainer.copy(alpha = .7f))
                            .border(1.dp, if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .6f)
                                else LocalAmbientVisuals.current.borderColor, shape)
                            .selectable(selected = selected, role = Role.RadioButton,
                                onClick = { onThemeModeChange(mode) })
                            .padding(horizontal = 6.dp, vertical = 12.dp),
                        contentAlignment = Alignment.Center,
                    ) {
                        Text(title, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                            color = if (selected) MaterialTheme.colorScheme.onPrimaryContainer
                                else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
        }
        Spacer(Modifier.height(2.dp))
        SectionLabel("数据与服务")
        AmbientPanel {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically) {
                    Text("和风天气 QWeather", style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.SemiBold)
                    StatusBadge("尚未连接")
                }
                Text("第一阶段仅展示区域环境演示数据，没有发起天气请求，也没有保存任何 API 密钥。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text("接入前将核对官方文档、身份认证、专属 API Host 与接口费用。",
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        SectionLabel("每个数值，都有来处")
        AmbientPanel {
            Column(Modifier.fillMaxWidth().padding(18.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
                DataExplanation("手机实测", "来自这部手机实际提供的传感器。无硬件时明确显示不支持。")
                DataExplanation("区域环境", "未来来自天气服务，代表一定区域的环境，不等同于手机周围的直接测量。当前均为演示。")
                DataExplanation("计算与估计", "合成强度由 XYZ 分量计算；去重力加速度来自系统计算，与含重力原始读数分别标注。")
                DataExplanation("定位速度", "开启后使用系统卫星定位提供的速度和精度；不依赖 Google 服务。仅在探测页且应用处于前台时定位，不保存或上传位置。")
                DataExplanation("海拔", "需要独立地形数据源。第一阶段不展示估算海拔，定位高度也不会被当作准确地形海拔。")
            }
        }
        Text("Ambient · 第一阶段\n个人环境探测工具 · 无账号 · 无自建服务器",
            style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun ThemeStyleOption(style: ThemeStyle, selected: Boolean, onClick: () -> Unit) {
    val shape = RoundedCornerShape(LocalAmbientVisuals.current.radius)
    AmbientPanel(
        Modifier.fillMaxWidth().clip(shape)
            .border(if (selected) 1.5.dp else 0.dp,
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = .75f) else Color.Transparent, shape)
            .selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    ) {
        Row(
            Modifier.fillMaxWidth().heightIn(min = 68.dp).padding(start = 12.dp, end = 8.dp, top = 10.dp, bottom = 10.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(42.dp, 44.dp).clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(style.previewColors()))
                .border(1.dp, Color.White.copy(alpha = .35f), RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Text(style.displayName, style = MaterialTheme.typography.titleSmall,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Medium)
                Text(style.description, style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            RadioButton(selected = selected, onClick = null)
        }
    }
}

/** Fixed swatches identify the original three palettes, independent of the active appearance. */
private fun ThemeStyle.previewColors(): List<Color> = when (this) {
    ThemeStyle.SUNLIT_AMBER -> listOf(Color(0xFFFCF7EE), Color(0xFFF0BB88), Color(0xFFEAD5B8))
    ThemeStyle.MISTY_DUSK -> listOf(Color(0xFFEAF1F9), Color(0xFFBBD1EA), Color(0xFFF0C9BD))
    ThemeStyle.FLOWING_GLASS -> listOf(Color(0xFF1E263A), Color(0xFF77719E), Color(0xFFB17B64))
}

@Composable
private fun DataExplanation(title: String, description: String) {
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Text(title, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
        Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

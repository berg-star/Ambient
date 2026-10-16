package com.ambient.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.ambient.app.ui.components.InstrumentSymbol
import com.ambient.app.ui.components.PageHeading
import com.ambient.app.ui.components.StatusBadge
import com.ambient.app.ui.theme.AmbientPanel
import com.ambient.app.ui.theme.LocalAmbientVisuals

@Composable
fun HistoryScreen(modifier: Modifier = Modifier) {
    val visuals = LocalAmbientVisuals.current
    Column(modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(26.dp)) {
        PageHeading("JOURNAL / 环境记录", "留住环境的切片", "让每一次观察，成为可以回看的记录。")
        AmbientPanel {
            Column(Modifier.fillMaxWidth().padding(horizontal = 26.dp, vertical = 36.dp),
                horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(18.dp)) {
                Surface(shape = RoundedCornerShape(visuals.radius), color = MaterialTheme.colorScheme.primary.copy(alpha = .1f)) {
                    Box(Modifier.size(76.dp), contentAlignment = Alignment.Center) {
                        InstrumentSymbol("history", Modifier.size(38.dp))
                    }
                }
                Text("你的环境日记，即将开启", style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Medium)
                StatusBadge("后续阶段开放")
                Text("第一阶段尚未保存任何历史数据。快照、趋势与导出功能将在真实数据接入后逐步完成。",
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Column(verticalArrangement = Arrangement.spacedBy(20.dp)) {
            PlannedFeature("01", "保存环境快照", "记录当时的环境指标、数据来源与观测时间。")
            PlannedFeature("02", "回看环境变化", "沿着时间查看真实记录，比较不同时刻的环境。")
            PlannedFeature("03", "导出为 CSV", "将自己的记录带走，方便整理与分析。")
        }
        Spacer(Modifier.height(4.dp))
    }
}

@Composable
private fun PlannedFeature(number: String, title: String, description: String) {
    androidx.compose.foundation.layout.Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Text(number, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelLarge,
            modifier = Modifier.padding(top = 3.dp))
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(title, style = MaterialTheme.typography.titleSmall)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

package com.ambient.app.ui.screens

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import com.ambient.app.ui.history.ExportStage
import com.ambient.app.ui.history.HistoryExportViewModel
import java.io.IOException

@Composable
fun HistoryExportDestinationHandler(model: HistoryExportViewModel) {
    val state by model.state.collectAsStateWithLifecycle()
    val lifecycle by LocalLifecycleOwner.current.lifecycle.currentStateFlow.collectAsState()
    val resolver = LocalContext.current.applicationContext.contentResolver
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")) { uri ->
        model.destination(if (uri == null) null else {
            { resolver.openOutputStream(uri, "wt") ?: throw IOException("No output stream") }
        })
    }
    LaunchedEffect(state.draft?.id, state.stage, lifecycle) {
        if (lifecycle.isAtLeast(Lifecycle.State.RESUMED) && state.stage == ExportStage.CHOOSING) model.claimPicker()?.let {
            try { picker.launch(it.fileName) } catch (_: Exception) { model.pickerFailed() }
        }
    }
}

@Composable
fun HistoryExportControl(model: HistoryExportViewModel, enabled: Boolean, trailingContent: @Composable () -> Unit = {}) {
    val state by model.state.collectAsStateWithLifecycle()
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedButton(onClick = model::prepare, enabled = enabled && !state.busy) {
            Text(when (state.stage) {
                ExportStage.IDLE -> "导出 CSV"
                ExportStage.PREPARING -> "正在整理记录…"
                ExportStage.CHOOSING -> "选择保存位置…"
                ExportStage.WRITING -> "正在导出…"
            })
        }
        trailingContent()
        }
        Text("全部快照 · 每项指标一行，保留时间、来源与备注", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
        state.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
    }
}

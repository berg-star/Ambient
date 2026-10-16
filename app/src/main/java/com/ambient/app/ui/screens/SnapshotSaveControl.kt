package com.ambient.app.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ambient.app.domain.history.SnapshotPolicy
import com.ambient.app.ui.history.HistoryViewModel
import com.ambient.app.ui.overview.EnvironmentUiState
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal fun historyTime(time: Instant): String = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
    .withZone(ZoneId.systemDefault()).format(time)

@Composable
fun SnapshotSaveControl(history: HistoryViewModel, environment: EnvironmentUiState, onOpenHistory: () -> Unit) {
    val action by history.actions.collectAsStateWithLifecycle()
    val reason = SnapshotPolicy.unavailableReason(environment.snapshot)
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            OutlinedButton(onClick = { history.prepareSave(environment.snapshot, environment.isCached) },
                enabled = reason == null && !action.busy && !environment.isRefreshing && !environment.isLocating,
                modifier = Modifier.weight(1f)) { Text("保存环境快照") }
            TextButton(onClick = onOpenHistory, enabled = !action.busy) { Text("查看记录") }
        }
        Text(reason ?: "保存当前显示的数据，不额外刷新。", style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
        action.message?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary) }
    }
    action.draft?.let { draft ->
        var note by rememberSaveable(draft.id) { mutableStateOf("") }
        AlertDialog(onDismissRequest = history::cancelSave,
            title = { Text("保存环境快照") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(draft.snapshot.areaLabel, style = MaterialTheme.typography.titleSmall)
                    Text("数据获取：${historyTime(draft.snapshot.updatedAt)}", style = MaterialTheme.typography.bodySmall)
                    Text(if (draft.wasCached) "来自缓存，保留原获取时间。" else "区域 API 数据，保留本次显示的原始数值。",
                        style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    OutlinedTextField(value = note, onValueChange = { note = it.take(SnapshotPolicy.MAX_NOTE_LENGTH) },
                        label = { Text("备注（可选）") }, minLines = 2, maxLines = 4,
                        enabled = !action.busy, modifier = Modifier.fillMaxWidth(),
                        supportingText = { Text("${note.length}/${SnapshotPolicy.MAX_NOTE_LENGTH}") })
                    action.error?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error) }
                    Text("保存时间在写入时记录，与数据获取时间分别展示。", style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            },
            confirmButton = { TextButton(onClick = { history.save(note) }, enabled = !action.busy) { Text(if (action.busy) "保存中…" else "确认保存") } },
            dismissButton = { TextButton(onClick = history::cancelSave, enabled = !action.busy) { Text("取消") } })
    }
}

package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentSnapshot
import kotlinx.coroutines.flow.Flow
import java.time.Instant

data class SnapshotDraft(val id: String, val snapshot: EnvironmentSnapshot, val wasCached: Boolean)

data class EnvironmentRecord(
    val id: String,
    val savedAt: Instant,
    val wasCached: Boolean,
    val snapshot: EnvironmentSnapshot,
    val note: String,
)

data class RecordSummary(
    val id: String,
    val areaLabel: String,
    val savedAt: Instant,
    val fetchedAt: Instant,
    val wasCached: Boolean,
    val temperature: String?,
    val temperatureUnit: String?,
    val aqi: String?,
    val note: String,
)

interface HistoryRepository {
    fun observeRecords(): Flow<List<RecordSummary>>
    fun observeRecord(id: String): Flow<EnvironmentRecord?>
    suspend fun save(draft: SnapshotDraft, note: String): String
    suspend fun updateNote(id: String, note: String)
    suspend fun delete(id: String)
}

class HistoryException(val userMessage: String) : Exception(userMessage)

object SnapshotPolicy {
    const val MAX_NOTE_LENGTH = 300
    fun unavailableReason(snapshot: EnvironmentSnapshot?): String? = when {
        snapshot == null -> "暂无可保存的区域数据。"
        snapshot.isDemo || snapshot.metrics.any { it.source == DataSource.DEMO } -> "演示数据不能保存为正式记录。"
        snapshot.areaLabel.isBlank() -> "区域信息缺失，暂时无法保存。"
        snapshot.metrics.none { it.source == DataSource.REGIONAL_API && it.value.trim() !in listOf("", "—", "--") } ->
            "尚无有效的区域指标，暂时无法保存。"
        else -> null
    }
    fun validate(snapshot: EnvironmentSnapshot) {
        unavailableReason(snapshot)?.let { throw HistoryException(it) }
    }
    fun normalizeNote(note: String): String {
        if (note.length > MAX_NOTE_LENGTH) throw HistoryException("备注最多 ${MAX_NOTE_LENGTH} 字。")
        return note.trim()
    }
}

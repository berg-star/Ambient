package com.ambient.app.domain.history

import java.io.OutputStream

data class HistoryExportDraft(
    val id: String,
    val fileName: String,
    val recordCount: Int,
    val metricCount: Int,
)

interface HistoryExportService {
    suspend fun prepare(): HistoryExportDraft
    /** The stream is opened, flushed and closed on IO; success includes a successful close. */
    suspend fun write(draft: HistoryExportDraft, openOutput: () -> OutputStream)
    suspend fun discard(draft: HistoryExportDraft)
}

package com.ambient.app.data.history

import android.content.Context
import com.ambient.app.domain.history.HistoryCsvWriter
import com.ambient.app.domain.history.HistoryException
import com.ambient.app.domain.history.HistoryExportDraft
import com.ambient.app.domain.history.HistoryExportService
import com.ambient.app.domain.history.HistoryRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.OutputStream
import java.time.Clock
import java.time.Duration
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.UUID

/** A frozen CSV in private cache avoids keeping the whole export in UI memory while choosing a destination. */
class HistoryCsvExporter internal constructor(
    private val directory: File,
    private val repository: HistoryRepository,
    private val clock: Clock = Clock.systemUTC(),
) : HistoryExportService {
    constructor(context: Context, repository: HistoryRepository) :
        this(File(context.applicationContext.cacheDir, "history-exports"), repository)

    private val lock = Mutex()
    private val pending = mutableMapOf<String, File>()

    override suspend fun prepare(): HistoryExportDraft = withContext(Dispatchers.IO) {
        lock.withLock {
            if (!directory.isDirectory && !directory.mkdirs()) error("Cannot create export cache")
            // Clean abandoned documents after process death, without touching any active draft.
            val cutoff = clock.instant().minus(Duration.ofDays(1)).toEpochMilli()
            directory.listFiles()?.filter { it.name.matches(Regex("[a-f0-9-]{36}\\.csv\\.tmp")) &&
                it.lastModified() < cutoff && it !in pending.values }?.forEach { it.delete() }
            val id = UUID.randomUUID().toString()
            val file = File(directory, "$id.csv.tmp")
            var ready = false
            try {
                var metrics = 0
                var records = 0
                val context = currentCoroutineContext()
                file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                    HistoryCsvWriter.writeHeader(writer)
                    records = repository.visitRecordsForExport { record ->
                        context.ensureActive()
                        metrics += HistoryCsvWriter.writeRecord(writer, record)
                    }
                }
                if (records == 0) throw HistoryException("还没有可导出的历史记录。")
                context.ensureActive()
                pending[id] = file
                ready = true
                val stamp = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss'Z'").withZone(ZoneOffset.UTC).format(clock.instant())
                HistoryExportDraft(id, "Ambient-history-$stamp.csv", records, metrics)
            } finally { if (!ready) file.delete() }
        }
    }

    override suspend fun write(draft: HistoryExportDraft, openOutput: () -> OutputStream) = withContext(Dispatchers.IO) {
        lock.withLock {
            val file = pending[draft.id] ?: throw HistoryException("导出准备已失效，请重新导出。")
            try {
                val context = currentCoroutineContext()
                openOutput().use { output ->
                    file.inputStream().use { input ->
                        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                        while (true) {
                            context.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            output.write(buffer, 0, read)
                        }
                    }
                    output.flush()
                }
            } finally {
                pending.remove(draft.id)
                file.delete()
            }
        }
    }

    override suspend fun discard(draft: HistoryExportDraft) = withContext(Dispatchers.IO) {
        lock.withLock { pending.remove(draft.id)?.delete(); Unit }
    }
}

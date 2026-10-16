package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import java.io.Writer

/** Streaming CSV output. The caller owns the writer and its UTF-8 encoding. */
object HistoryCsvWriter {
    const val FORMAT_VERSION = "1"

    val headers: List<String> = listOf(
        "导出格式版本", "快照ID", "区域", "保存时间(UTC)", "数据获取时间(UTC)", "观测时间(UTC)",
        "缓存快照", "演示快照", "备注", "指标ID", "指标", "数值", "单位", "数据来源代码", "数据来源",
        "指标说明", "风向来向角度(°)", "归因链接", "数据提示",
    )

    fun writeHeader(writer: Writer) {
        writer.write("\uFEFF")
        writeRow(writer, headers)
    }

    /** Returns the number of data rows written; an empty snapshot still occupies one row. */
    fun writeRecord(writer: Writer, record: EnvironmentRecord): Int {
        val snapshot = record.snapshot
        val metadata = listOf(
            FORMAT_VERSION,
            record.id,
            snapshot.areaLabel,
            record.savedAt.toString(),
            snapshot.updatedAt.toString(),
            snapshot.observedAt?.toString().orEmpty(),
            record.wasCached.toString(),
            snapshot.isDemo.toString(),
            record.note,
        )
        val annotations = listOf(snapshot.attributions.joinToString("\n"), snapshot.notices.joinToString("\n"))
        if (snapshot.metrics.isEmpty()) {
            writeRow(writer, metadata + List(8) { "" } + annotations)
            return 1
        }
        snapshot.metrics.forEach { metric ->
            writeRow(writer, metadata + metric.cells() + annotations, numericColumns = setOf(11, 16))
        }
        return snapshot.metrics.size
    }

    private fun EnvironmentMetric.cells() = listOf(
        id, label, value, unit, source.name, source.displayLabel(), description,
        windFromDegrees?.toString().orEmpty(),
    )

    private fun DataSource.displayLabel(): String = when (this) {
        DataSource.DEMO -> "演示数据"
        DataSource.DEVICE -> "手机实测"
        DataSource.REGIONAL_API -> "区域 API"
        DataSource.ESTIMATED -> "算法估计"
    }

    private fun writeRow(writer: Writer, cells: List<String>, numericColumns: Set<Int> = emptySet()) {
        cells.forEachIndexed { index, value ->
            if (index > 0) writer.write(",")
            val safeValue = spreadsheetSafe(value, index in numericColumns)
            writer.write("\"")
            writer.write(safeValue.replace("\"", "\"\""))
            writer.write("\"")
        }
        writer.write("\r\n")
    }

    /** Keep signed measurements numeric while preventing text from becoming spreadsheet formulas. */
    private fun spreadsheetSafe(value: String, numeric: Boolean): String {
        if (numeric && value.toDoubleOrNull()?.isFinite() == true) return value
        val dangerous = value.firstOrNull() in listOf('\t', '\r', '\n') ||
            value.trimStart().firstOrNull() in listOf('=', '+', '-', '@')
        return if (dangerous) "'$value" else value
    }
}

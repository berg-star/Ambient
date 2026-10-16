package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class HistoryCsvWriterTest {
    private val fetchedAt = Instant.parse("2026-10-16T08:15:12.123456789Z")
    private val savedAt = Instant.parse("2026-10-16T10:00:00Z")
    private val metric = EnvironmentMetric("temperature", "室外温度", "-2.50", "°C", DataSource.REGIONAL_API, "区域 API 提供")
    private val record = EnvironmentRecord(
        "snapshot-1", savedAt, true,
        EnvironmentSnapshot("北京", fetchedAt, listOf(metric), isDemo = false), "散步",
    )

    @Test fun headerHasBomExactColumnOrderAndCrLf() {
        val writer = StringWriter()
        HistoryCsvWriter.writeHeader(writer)
        val text = writer.toString()
        assertTrue(text.startsWith("\uFEFF\"导出格式版本\","))
        assertTrue(text.endsWith("\r\n"))
        assertEquals(listOf(listOf(
            "导出格式版本", "快照ID", "区域", "保存时间(UTC)", "数据获取时间(UTC)", "观测时间(UTC)",
            "缓存快照", "演示快照", "备注", "指标ID", "指标", "数值", "单位", "数据来源代码", "数据来源",
            "指标说明", "风向来向角度(°)", "归因链接", "数据提示",
        )), parseCsv(text))
    }

    @Test fun chineseQuotesCommasNewlinesAndPhysicalUnitsRoundTrip() {
        val special = "室内,\"靠窗\"\n第二行\r\n第三行"
        val snapshot = record.snapshot.copy(
            areaLabel = special,
            metrics = listOf(
                metric.copy(id = "unknown.future", label = "地磁场", value = "42.80", unit = "μT", description = special),
                metric.copy(id = "linear_acceleration", label = "去重力加速度", value = "0.05", unit = "m/s²"),
            ),
            attributions = listOf("https://example.test/a?x=1,y=2", "https://example.test/\"b\""),
            notices = listOf("空气质量暂缺", special),
        )
        val rows = export(record.copy(snapshot = snapshot, note = special))
        assertEquals(3, rows.size)
        for (row in rows.drop(1)) {
            assertEquals(19, row.size)
            assertEquals(special, row[2])
            assertEquals(special, row[8])
            assertEquals(snapshot.attributions.joinToString("\n"), row[17])
            assertEquals(snapshot.notices.joinToString("\n"), row[18])
        }
        assertEquals("unknown.future", rows[1][9])
        assertEquals("μT", rows[1][12])
        assertEquals(special, rows[1][15])
        assertEquals("m/s²", rows[2][12])
    }

    @Test fun cachedDataRetainsSeparateUtcTimesAndMissingObservationOrAqi() {
        val snapshot = record.snapshot.copy(metrics = listOf(metric, metric.copy(id = "aqi", label = "AQI", value = "—", unit = "")))
        val rows = export(record.copy(snapshot = snapshot)).drop(1)
        assertEquals("1", rows[0][0])
        assertEquals(savedAt.toString(), rows[0][3])
        assertEquals(fetchedAt.toString(), rows[0][4])
        assertEquals("", rows[0][5])
        assertEquals("true", rows[0][6])
        assertEquals("false", rows[0][7])
        assertEquals("—", rows[1][11])
        assertEquals("", rows[1][12])
        assertEquals("", rows[1][16])
        val observed = Instant.parse("2026-10-16T16:00:00+08:00")
        assertEquals("2026-10-16T08:00:00Z", export(record.copy(snapshot = snapshot.copy(observedAt = observed)))[1][5])
    }

    @Test fun sourceCodesLabelsAndWindBearingsArePreserved() {
        val sources = listOf(
            DataSource.DEMO to "演示数据", DataSource.DEVICE to "手机实测",
            DataSource.REGIONAL_API to "区域 API", DataSource.ESTIMATED to "算法估计",
        )
        val snapshot = record.snapshot.copy(
            metrics = sources.map { (source, _) -> metric.copy(source = source, windFromDegrees = 67.5) },
            isDemo = true,
        )
        val rows = export(record.copy(snapshot = snapshot)).drop(1)
        sources.forEachIndexed { index, (source, label) ->
            assertEquals(source.name, rows[index][13])
            assertEquals(label, rows[index][14])
            assertEquals("67.5", rows[index][16])
            assertEquals("true", rows[index][7])
        }
    }

    @Test fun formulaLikeTextIsNeutralizedAcrossMetadataAndMetricCells() {
        val dangerous = listOf("=HYPERLINK(\"https://example.test\")", " +SUM(1,2)", "-2+3", "\ttext", "\rtext", "\ntext", "  @SUM(A1)")
        dangerous.forEach { value ->
            val snapshot = record.snapshot.copy(
                areaLabel = value,
                metrics = listOf(metric.copy(id = value, label = value, value = value, unit = value, description = value)),
                attributions = listOf(value), notices = listOf(value),
            )
            val row = export(record.copy(id = value, snapshot = snapshot, note = value))[1]
            for (index in listOf(1, 2, 8, 9, 10, 11, 12, 15, 17, 18)) assertEquals("column $index", "'$value", row[index])
        }
    }

    @Test fun finiteNumericMeasurementsKeepTheirOriginalSpellingIncludingNegativeValues() {
        val values = listOf("-0.0500", "+2.500", "-2.5e-3", "  -1.2", "0", "1.7976931348623157E308")
        val snapshot = record.snapshot.copy(metrics = values.map { metric.copy(value = it, windFromDegrees = -0.0) })
        val rows = export(record.copy(snapshot = snapshot, note = "-2.50")).drop(1)
        values.forEachIndexed { index, value ->
            assertEquals(value, rows[index][11])
            assertEquals("-0.0", rows[index][16])
            assertEquals("'-2.50", rows[index][8])
        }
        val nonFinite = export(record.copy(snapshot = record.snapshot.copy(metrics = listOf(metric.copy(value = "-Infinity")))))[1]
        assertEquals("'-Infinity", nonFinite[11])
    }

    @Test fun ordinaryTextAndEmptyCellsAreNotModified() {
        val row = export(record.copy(note = "  普通备注  ", snapshot = record.snapshot.copy(metrics = listOf(metric.copy(value = "")))))[1]
        assertEquals("  普通备注  ", row[8])
        assertEquals("北京", row[2])
        assertEquals("", row[11])
        assertEquals("区域 API 提供", row[15])
    }

    @Test fun multipleRecordsStreamInOrderWithoutClosingOrFlushingCallerWriter() {
        val writer = object : StringWriter() {
            var wasClosed = false
            var wasFlushed = false
            override fun close() { wasClosed = true }
            override fun flush() { wasFlushed = true }
        }
        HistoryCsvWriter.writeHeader(writer)
        assertEquals(1, HistoryCsvWriter.writeRecord(writer, record))
        assertEquals(2, HistoryCsvWriter.writeRecord(writer, record.copy(id = "second", snapshot = record.snapshot.copy(metrics = listOf(metric, metric)))))
        assertFalse(writer.wasClosed)
        assertFalse(writer.wasFlushed)
        val rows = parseCsv(writer.toString())
        assertEquals(listOf("snapshot-1", "second", "second"), rows.drop(1).map { it[1] })
        assertEquals(1, writer.toString().count { it == '\uFEFF' })
    }

    @Test fun noMetricsStillExportsRecordMetadataWithoutInventingAReading() {
        val writer = StringWriter()
        HistoryCsvWriter.writeHeader(writer)
        assertEquals(1, HistoryCsvWriter.writeRecord(writer, record.copy(snapshot = record.snapshot.copy(metrics = emptyList(), notices = listOf("暂缺")))))
        val row = parseCsv(writer.toString())[1]
        assertEquals(19, row.size)
        assertEquals("snapshot-1", row[1])
        assertEquals("散步", row[8])
        assertTrue(row.subList(9, 17).all { it.isEmpty() })
        assertEquals("暂缺", row[18])
    }

    private fun export(record: EnvironmentRecord): List<List<String>> = StringWriter().let { writer ->
        HistoryCsvWriter.writeHeader(writer)
        HistoryCsvWriter.writeRecord(writer, record)
        parseCsv(writer.toString())
    }

    /** Independent CSV reader: understands escaped quotes and line breaks inside quoted cells. */
    private fun parseCsv(csv: String): List<List<String>> {
        val input = csv.removePrefix("\uFEFF")
        val rows = mutableListOf<List<String>>()
        val row = mutableListOf<String>()
        val cell = StringBuilder()
        var quoted = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            if (quoted) {
                if (char == '"') {
                    if (input.getOrNull(index + 1) == '"') { cell.append('"'); index++ }
                    else quoted = false
                } else cell.append(char)
            } else when (char) {
                '"' -> { check(cell.isEmpty()); quoted = true }
                ',' -> { row += cell.toString(); cell.clear() }
                '\r' -> {
                    check(input.getOrNull(index + 1) == '\n')
                    row += cell.toString(); cell.clear()
                    rows += row.toList(); row.clear()
                    index++
                }
                '\n' -> error("Bare LF outside a quoted cell")
                else -> cell.append(char)
            }
            index++
        }
        check(!quoted && cell.isEmpty() && row.isEmpty()) { "CSV must end with a complete CRLF row" }
        return rows
    }
}

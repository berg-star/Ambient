package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.TreeMap

data class HistoryFilter(
    val areaLabel: String? = null,
    val fromDate: LocalDate? = null,
    val toDate: LocalDate? = null,
) {
    init {
        require(fromDate == null || toDate == null || !fromDate.isAfter(toDate)) {
            "开始日期不能晚于结束日期。"
        }
    }

    /** Calendar days in the supplied zone, including the entire selected end day. */
    fun bounds(zone: ZoneId): HistoryBounds = HistoryBounds(
        fromInclusive = fromDate?.atStartOfDay(zone)?.toInstant(),
        untilExclusive = toDate?.plusDays(1)?.atStartOfDay(zone)?.toInstant(),
    )

    fun includes(areaLabel: String, fetchedAt: Instant, zone: ZoneId): Boolean {
        val bounds = bounds(zone)
        return (this.areaLabel == null || this.areaLabel == areaLabel) &&
            (bounds.fromInclusive == null || !fetchedAt.isBefore(bounds.fromInclusive)) &&
            (bounds.untilExclusive == null || fetchedAt.isBefore(bounds.untilExclusive))
    }
}

data class HistoryBounds(val fromInclusive: Instant?, val untilExclusive: Instant?)

enum class TrendMetric(val metricId: String, val label: String, val unit: String) {
    TEMPERATURE("temperature", "温度", "°C"),
    HUMIDITY("humidity", "湿度", "%"),
    PRESSURE("pressure", "海平面气压", "hPa"),
    AQI("aqi", "空气质量", "AQI"),
}

data class TrendPoint(
    val recordId: String,
    val areaLabel: String,
    val fetchedAt: Instant,
    val savedAt: Instant,
    val observedAt: Instant?,
    val wasCached: Boolean,
    val metric: EnvironmentMetric?,
    val value: Double?,
    val missingReason: String?,
)

data class HistoryTrend(
    val areaLabel: String,
    val metric: TrendMetric,
    val points: List<TrendPoint> = emptyList(),
    val duplicateCount: Int = 0,
    val hasOlder: Boolean = false,
)

/**
 * Keeps a bounded number of original fetches, independent of visit order or repeated saves.
 * A missing/invalid metric remains a timestamped gap, so a renderer cannot silently fill it.
 */
class HistoryTrendAccumulator(
    filter: HistoryFilter,
    private val metric: TrendMetric,
    zone: ZoneId,
    private val maxPoints: Int = 120,
) {
    private val areaLabel = requireNotNull(filter.areaLabel) { "趋势图需要选择一个区域。" }
    private val bounds = filter.bounds(zone)
    private val groups = TreeMap<Instant, Group>()
    private var hasOlder = false

    init {
        require(areaLabel.isNotBlank()) { "趋势图需要选择一个区域。" }
        require(maxPoints > 0) { "趋势点上限必须大于 0。" }
    }

    fun add(record: EnvironmentRecord) {
        val snapshot = record.snapshot
        val fetchedAt = snapshot.updatedAt
        if (snapshot.isDemo || snapshot.metrics.any { it.source == DataSource.DEMO } || snapshot.areaLabel != areaLabel ||
            bounds.fromInclusive?.let { fetchedAt.isBefore(it) } == true ||
            bounds.untilExclusive?.let { !fetchedAt.isBefore(it) } == true
        ) return

        groups[fetchedAt]?.let { group ->
            group.count += 1
            if (compareRecords(record, group.record) < 0) group.record = record
            return
        }
        if (groups.size == maxPoints && fetchedAt.isBefore(groups.firstKey())) {
            hasOlder = true
            return
        }
        groups[fetchedAt] = Group(record)
        if (groups.size > maxPoints) {
            groups.pollFirstEntry()
            hasOlder = true
        }
    }

    fun build(): HistoryTrend = HistoryTrend(
        areaLabel = areaLabel,
        metric = metric,
        points = groups.values.map { it.record.toPoint() },
        duplicateCount = groups.values.sumOf { it.count - 1 },
        hasOlder = hasOlder,
    )

    private fun EnvironmentRecord.toPoint(): TrendPoint {
        val measurement = snapshot.metrics.firstOrNull { it.id == metric.metricId }
        val parsed = measurement?.value?.trim()?.toDoubleOrNull()
        val missingReason = when {
            measurement == null || measurement.value.trim() in listOf("", "—", "--") -> "暂无该指标数据"
            measurement.source != DataSource.REGIONAL_API -> "该读数并非区域环境 API 数据"
            measurement.unit != metric.unit -> "单位与趋势图不一致（需要 ${metric.unit}）"
            parsed == null || !parsed.isFinite() -> "数值不可解析或不为有限数"
            !validValue(parsed) -> "数值超出有效范围"
            else -> null
        }
        return TrendPoint(
            recordId = id,
            areaLabel = snapshot.areaLabel,
            fetchedAt = snapshot.updatedAt,
            savedAt = savedAt,
            observedAt = snapshot.observedAt,
            wasCached = wasCached,
            metric = measurement,
            value = if (missingReason == null) parsed else null,
            missingReason = missingReason,
        )
    }

    private fun validValue(value: Double): Boolean = when (metric) {
        TrendMetric.TEMPERATURE -> value >= -273.15
        TrendMetric.HUMIDITY -> value in 0.0..100.0
        TrendMetric.PRESSURE -> value > 0.0
        TrendMetric.AQI -> value >= 0.0
    }

    private fun compareRecords(first: EnvironmentRecord, second: EnvironmentRecord): Int =
        compareValuesBy(first, second, { it.wasCached }, { it.savedAt }, { it.id })

    private data class Group(var record: EnvironmentRecord, var count: Int = 1)
}

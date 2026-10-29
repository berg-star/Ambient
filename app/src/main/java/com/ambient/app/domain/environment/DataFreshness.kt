package com.ambient.app.domain.environment

import java.time.Duration
import java.time.Instant

enum class Freshness { RECENT, AGING, OLD, UNKNOWN, CLOCK_ERROR, MISSING, DEMO }

data class MetricTiming(
    val fetchedAt: Instant?,
    val observedAt: Instant?,
    val freshness: Freshness,
    /** Older versions stored only the completion time of the entire batch. */
    val batchTimeOnly: Boolean = false,
)

/** UI refresh reminders, not promises about the provider's measurement accuracy or update schedule. */
fun metricTiming(metric: EnvironmentMetric, snapshot: EnvironmentSnapshot?, now: Instant): MetricTiming {
    if (metric.source == DataSource.DEMO) return MetricTiming(null, null, Freshness.DEMO)
    if (metric.value.isBlank() || metric.value == "—") return MetricTiming(null, null, Freshness.MISSING)
    val regional = metric.source == DataSource.REGIONAL_API
    val fetched = metric.fetchedAtMillis?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    val observed = metric.observedAtMillis?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)
    // Never borrow a batch weather time for positioning, or for an unknown historical air observation.
    val batch = if (regional && fetched == null) snapshot?.updatedAt else null
    val reference = observed ?: fetched ?: batch
    val age = reference?.let { Duration.between(it, now).seconds }
    val threshold = if (metric.id in airMetricIds) 3600L else 600L
    val status = when {
        listOfNotNull(fetched, observed, batch).any { it.isAfter(now) } -> Freshness.CLOCK_ERROR
        age == null -> Freshness.UNKNOWN
        age >= threshold -> Freshness.OLD
        batch != null -> Freshness.UNKNOWN
        age < 60 -> Freshness.RECENT
        else -> Freshness.AGING
    }
    return MetricTiming(fetched ?: batch, observed, status, batchTimeOnly = batch != null)
}

val airMetricIds = setOf("aqi", "pm25", "pm10")

/** Location remains a one-shot fix; neither weather refresh nor UI time can update it. */
fun locationFixTime(area: EnvironmentArea?): Instant? = area?.takeIf { it.fromDevice }
    ?.let { it.locatedAtMillis ?: it.positioningHeightAtMillis }
    ?.takeIf { it > 0 }?.let(Instant::ofEpochMilli)

fun freshnessLabel(timing: MetricTiming, isCached: Boolean): String {
    val label = when (timing.freshness) {
    Freshness.DEMO -> "演示数据"
    Freshness.MISSING -> "暂无有效数据"
    Freshness.CLOCK_ERROR -> "时间异常 · 请核对设备时间"
    Freshness.OLD -> if (timing.observedAt != null) "较早观测 · 建议刷新" else "获取较早 · 建议刷新"
    Freshness.UNKNOWN -> if (timing.batchTimeOnly) "分项时间未记录" else "时间未记录"
    Freshness.RECENT -> if (isCached) "本地缓存" else "刚获取"
    Freshness.AGING -> if (isCached) "本地缓存" else "已获取"
    }
    return if (isCached && timing.freshness in listOf(Freshness.OLD, Freshness.UNKNOWN, Freshness.CLOCK_ERROR))
        "本地缓存 · $label" else label
}

package com.ambient.app.domain.history

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentMetric

data class ComparisonMetric(
    val id: String,
    val label: String,
    val left: EnvironmentMetric?,
    val right: EnvironmentMetric?,
    /** Absolute numeric difference B minus A, never relative percentage change. */
    val delta: Double?,
    val deltaUnit: String,
    val explanation: String?,
)

data class HistoryComparison(
    val left: EnvironmentRecord,
    val right: EnvironmentRecord,
    val metrics: List<ComparisonMetric>,
    val notice: String?,
)

object HistoryComparisonBuilder {
    private val scalarIds = setOf("temperature", "humidity", "pressure", "aqi", "pm25", "pm10", "windSpeed", "uv")

    fun build(left: EnvironmentRecord, right: EnvironmentRecord): HistoryComparison {
        // Preserve the first original object for each ID and A's order, then append B-only IDs.
        val leftMetrics = left.snapshot.metrics.distinctBy { it.id }.associateBy { it.id }
        val rightMetrics = right.snapshot.metrics.distinctBy { it.id }.associateBy { it.id }
        val ids = (leftMetrics.keys + rightMetrics.keys).distinct()
        val missingArea = left.snapshot.areaLabel.isBlank() || right.snapshot.areaLabel.isBlank()
        val differentAreas = left.snapshot.areaLabel != right.snapshot.areaLabel
        val sameFetch = !missingArea && !differentAreas && left.snapshot.updatedAt == right.snapshot.updatedAt
        val hasDemo = left.isDemo() || right.isDemo()
        val notices = buildList {
            if (missingArea) add("区域信息缺失，仅并排展示。")
            if (differentAreas) add("不同区域仅并排展示，不计算同一地点的时间变化。")
            if (sameFetch) add("两份记录的数据获取时间相同（含缓存副本），属于同一次获取，不是新观测。")
            if (hasDemo) add("演示数据不参与差值计算。")
        }
        val metrics = ids.map { id ->
            val a = leftMetrics[id]
            val b = rightMetrics[id]
            val aValue = a?.value?.trim()?.toDoubleOrNull()
            val bValue = b?.value?.trim()?.toDoubleOrNull()
            val difference = if (aValue != null && bValue != null) bValue - aValue else null
            val explanation = when {
                a == null || b == null -> "一侧缺少该指标，不计算差值。"
                hasDemo -> "演示数据不参与差值计算。"
                missingArea -> "区域信息缺失，仅并排展示。"
                differentAreas -> "区域不同，仅并排展示。"
                sameFetch -> "同一次数据获取，不是新观测。"
                id !in scalarIds -> "该指标仅并排展示，不计算数值差。"
                a.source != DataSource.REGIONAL_API || b.source != DataSource.REGIONAL_API -> "两侧须均为区域环境 API 数据。"
                a.unit != b.unit -> "两侧单位不同，不换算或计算差值。"
                id == "aqi" && a.label != b.label -> "AQI 标准或标签不同，暂不计算差值。"
                aValue == null || bValue == null || !aValue.isFinite() || !bValue.isFinite() -> "一侧数值缺失、不可解析或不为有限数。"
                !validValue(id, aValue, a.unit) || !validValue(id, bValue, b.unit) -> "一侧数值超出有效范围。"
                difference == null || !difference.isFinite() -> "差值超出可表示范围。"
                else -> null
            }
            ComparisonMetric(
                id = id,
                label = a?.label ?: requireNotNull(b).label,
                left = a,
                right = b,
                delta = if (explanation == null) difference?.let { if (it == 0.0) 0.0 else it } else null,
                deltaUnit = if (id == "humidity" && a?.unit == "%" && b?.unit == "%") "个百分点" else a?.unit ?: b?.unit.orEmpty(),
                explanation = explanation,
            )
        }
        return HistoryComparison(left, right, metrics, notices.takeIf { it.isNotEmpty() }?.joinToString(" "))
    }

    private fun EnvironmentRecord.isDemo(): Boolean = snapshot.isDemo || snapshot.metrics.any { it.source == DataSource.DEMO }

    private fun validValue(id: String, value: Double, unit: String): Boolean = when (id) {
        "temperature" -> when (unit) {
            "°C" -> value >= -273.15
            "K" -> value >= 0.0
            "°F" -> value >= -459.67
            else -> true
        }
        "humidity" -> if (unit == "%") value in 0.0..100.0 else value >= 0.0
        "pressure" -> value > 0.0
        "aqi", "pm25", "pm10", "windSpeed", "uv" -> value >= 0.0
        else -> false
    }
}

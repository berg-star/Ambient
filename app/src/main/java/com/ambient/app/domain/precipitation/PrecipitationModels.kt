package com.ambient.app.domain.precipitation

import com.ambient.app.domain.environment.EnvironmentArea
import java.time.Instant

/** Forecast intervals, never phone measurements. Null is missing, including probability. */
data class PrecipitationPoint(val time: Long, val amountMm: Double?, val probability: Double? = null,
                              val type: String = "unknown")
data class PrecipitationForecast(val points: List<PrecipitationPoint>, val fetchedAt: Long,
    val updatedAt: Long? = null, val summary: String? = null, val attributions: List<String> = emptyList())
data class PrecipitationSnapshot(val area: EnvironmentArea, val minutely: PrecipitationForecast? = null,
                                 val hourly: PrecipitationForecast? = null)
data class PrecipitationResult(val snapshot: PrecipitationSnapshot?, val messages: List<String> = emptyList(),
                               val isCached: Boolean = false)
interface PrecipitationDataService {
    suspend fun minutely(area: EnvironmentArea): PrecipitationForecast
    suspend fun hourly(area: EnvironmentArea): PrecipitationForecast
}
fun interface PrecipitationRepository { suspend fun loadPrecipitation(force: Boolean): PrecipitationResult }

enum class ForecastRange(val label: String, val hours: Int, val stepMinutes: Int) {
    TWO_HOURS("2 小时", 2, 5), DAY("24 小时", 24, 60), THREE_DAYS("72 小时", 72, 60)
}

/** Preserve real forecast timestamps and gaps; never interpolate or zero-fill missing data. */
fun forecastPoints(forecast: PrecipitationForecast?, range: ForecastRange, now: Instant): List<PrecipitationPoint> {
    val start = now.toEpochMilli()
    val end = start + range.hours * 3_600_000L
    return forecast?.points.orEmpty().filter {
        it.time + range.stepMinutes * 60_000L > start && it.time < end
    }.sortedBy { it.time }.take(if (range == ForecastRange.TWO_HOURS) 24 else range.hours)
}

fun forecastStale(forecast: PrecipitationForecast, range: ForecastRange, now: Instant): Boolean {
    val age = now.toEpochMilli() - forecast.fetchedAt
    val ttl = if (range == ForecastRange.TWO_HOURS) 300_000L else 3_600_000L
    val providerAge = forecast.updatedAt?.let { now.toEpochMilli() - it }
    return age !in 0 until ttl || (range == ForecastRange.TWO_HOURS &&
        providerAge != null && providerAge !in -60_000L until 900_000L)
}

fun precipitationType(type: String): String = when (type) {
    "rain" -> "雨"; "snow" -> "雪"; "ice" -> "冰粒或冻雨"; "mixed" -> "混合降水"
    "none" -> "无降水"; else -> "类型未知"
}

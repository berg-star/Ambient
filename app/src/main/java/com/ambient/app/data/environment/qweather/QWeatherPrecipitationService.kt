package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.precipitation.*
import com.google.gson.JsonObject
import java.time.Clock
import java.time.OffsetDateTime

class QWeatherPrecipitationService(private val client: QWeatherClient,
    private val clock: Clock = Clock.systemUTC()) : PrecipitationDataService {
    override suspend fun minutely(area: EnvironmentArea): PrecipitationForecast = decodeMinutely(client.minutely(area), clock.millis())
    override suspend fun hourly(area: EnvironmentArea): PrecipitationForecast = decodeHourly(client.hourly(area), clock.millis())

    companion object {
        private fun timestamp(text: String): Long? = runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()
        private fun amount(value: Double?): Double? = value?.takeIf { it.isFinite() && it >= 0 }
        private fun points(json: JsonObject, array: String, hourly: Boolean): List<PrecipitationPoint> = json.array(array)
            .filter { it.isJsonObject }.mapNotNull {
                val p = it.asJsonObject
                val time = timestamp(p.string(if (hourly) "forecastTime" else "fxTime")) ?: return@mapNotNull null
                val precipitation = if (hourly) p.obj("precipitation") else p
                val mm = if (hourly) precipitation.obj("amount").let { a ->
                    if (a.string("unit") == "mm") amount(a.number("value")) else null
                } else amount(p.number("precip"))
                PrecipitationPoint(time, mm, if (hourly) precipitation.number("probability")?.takeIf { v -> v in 0.0..1.0 } else null,
                    precipitation.string("type").takeIf { t -> t in setOf("rain", "snow", "ice", "mixed", "none") } ?: "unknown")
            }.distinctBy { it.time }.sortedBy { it.time }.take(if (hourly) 73 else 25)

        internal fun decodeMinutely(json: JsonObject, fetched: Long): PrecipitationForecast {
            if (json.string("code") != "200") throw EnvironmentException(json.string("code").toIntOrNull()?.let {
                QWeatherClient.httpError(it) } ?: "分钟降水接口返回异常状态。")
            val points = points(json, "minutely", false)
            if (points.isEmpty()) throw EnvironmentException("分钟降水接口暂无有效预报，不能判断为无降水。")
            val attribution = listOf("sources", "license").flatMap { json.obj("refer").array(it) }
                .filter { it.isJsonPrimitive }.map { it.asString.take(300) }.distinct().take(12)
            return PrecipitationForecast(points, fetched, timestamp(json.string("updateTime")),
                json.string("summary").take(300).takeIf { it.isNotBlank() }, attribution)
        }
        internal fun decodeHourly(json: JsonObject, fetched: Long): PrecipitationForecast {
            val points = points(json, "hours", true)
            if (points.isEmpty()) throw EnvironmentException("逐小时接口暂无有效预报，不能判断为无降水。")
            val attribution = json.obj("metadata").array("attributions").filter { it.isJsonPrimitive }
                .map { it.asString.take(300) }.distinct().take(12)
            // v1 does not document an issue/update timestamp. Never substitute fetch time for one.
            return PrecipitationForecast(points, fetched, attributions = attribution)
        }
    }
}

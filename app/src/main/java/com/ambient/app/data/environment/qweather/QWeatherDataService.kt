package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentDataService
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.environment.EnvironmentDetails
import com.google.gson.JsonObject
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import java.time.Clock
import java.time.Instant
import java.util.Locale

class QWeatherDataService(
    private val client: QWeatherClient,
    private val area: EnvironmentArea,
    private val clock: Clock = Clock.systemUTC(),
) : EnvironmentDataService {
    private data class AirResult(val data: JsonObject?, val fetchedAt: Instant?, val warning: String? = null)

    override suspend fun fetch(): EnvironmentSnapshot = coroutineScope {
        val air = async {
            try { AirResult(client.air(area), clock.instant()) }
            catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { AirResult(null, null, "空气质量未更新：${e.userMessage}") }
        }
        val weather = client.weather(area)
        val weatherFetchedAt = clock.instant()
        val result = air.await()
        QWeatherDecoder.decode(weather, result.data, area, clock.instant(), result.warning,
            weatherFetchedAt = weatherFetchedAt, airFetchedAt = result.fetchedAt)
    }
}

/** Maps only documented fields. Missing values remain missing; no sensor estimates here. */
object QWeatherDecoder {
    fun decode(weather: JsonObject, air: JsonObject?, area: EnvironmentArea, fetchedAt: Instant,
               airWarning: String? = null, weatherFetchedAt: Instant = fetchedAt,
               airFetchedAt: Instant? = fetchedAt.takeIf { air != null }): EnvironmentSnapshot {
        if (!weather.has("temperature") || !weather.has("metadata"))
            throw EnvironmentException("天气服务返回了无法识别的数据，请稍后重试。")
        val metrics = mutableListOf<EnvironmentMetric>()
        fun add(id: String, label: String, value: Double?, unit: String, description: String, decimals: Int = 1) {
            metrics += EnvironmentMetric(id, label, value?.let { String.format(Locale.US, "%.${decimals}f", it) }
                ?: "—", unit, DataSource.REGIONAL_API, description)
        }
        fun measurement(key: String, id: String, label: String, unit: String, description: String, nonNegative: Boolean = false) {
            val data = weather.obj(key)
            val value = data.number("value")?.takeIf { data.string("unit") == unit && (!nonNegative || it >= 0) }
            add(id, label, value, unit, description)
        }
        measurement("temperature", "temperature", "室外温度", "°C", "和风天气提供的区域气温，非手机温度传感器读数。")
        val detail = EnvironmentDetails.definitions.associateBy { it.id }
        for (key in listOf("feelsLike", "dewPoint", "windGust")) {
            val definition = detail.getValue(key)
            measurement(key, key, definition.label, definition.unit, definition.meaning, key == "windGust")
        }
        val visibility = weather.obj("visibility")
        val meters = visibility.number("value")?.takeIf { it >= 0 && visibility.string("unit") == "m" }
        val useKm = meters != null && meters >= 1000
        add("visibility", "能见度", meters?.let { if (useKm) it / 1000 else it }, if (useKm) "km" else "m",
            detail.getValue("visibility").meaning, if (useKm) 2 else 1)
        add("cloudCover", "云量", weather.number("cloudCover")?.takeIf { it in 0.0..1.0 }?.times(100), "%",
            detail.getValue("cloudCover").meaning, 0)
        add("humidity", "相对湿度", weather.number("humidity")?.takeIf { it in 0.0..1.0 }?.times(100), "%",
            "和风区域相对湿度；接口的 0–1 比例已转换为百分比。", 0)
        measurement("pressure", "pressure", "海平面气压", "hPa", "和风提供的海平面气压，经过海平面订正，不能直接与手机所在地的气压读数比较。", true)
        val index = air?.array("indexes")?.map { it.asJsonObject }?.firstOrNull { it.string("code") == "cn-mee" }
        metrics += EnvironmentMetric("aqi", "空气质量 · 中国", index?.string("aqiDisplay")?.ifBlank { "—" } ?: "—",
            "AQI", DataSource.REGIONAL_API,
            "和风区域空气质量，采用 cn-mee 中国标准。${index?.string("category").orEmpty()}；不将其他国家指数当作中国 AQI。")
        for ((code, id, label) in listOf(Triple("pm2p5", "pm25", "PM2.5"), Triple("pm10", "pm10", "PM10"))) {
            val p = air?.array("pollutants")?.map { it.asJsonObject }?.firstOrNull { it.string("code") == code }?.obj("concentration")
            val value = p?.number("value")?.takeIf { it >= 0 && p.string("unit") in listOf("μg/m³", "μg/m3", "µg/m³", "µg/m3") }
            add(id, label, value, "μg/m³", "和风提供的区域颗粒物浓度，非手机直接检测。")
        }
        val wind = weather.obj("wind")
        val speed = wind.obj("speed")
        add("windSpeed", "风速", speed.number("value")?.takeIf { it >= 0 && speed.string("unit") == "m/s" }, "m/s",
            "区域风速，不代表手机周围的瞬时风速。")
        val direction = wind.obj("direction")
        val compass = mapOf("n" to "北", "nne" to "北东北", "ne" to "东北", "ene" to "东东北", "e" to "东",
            "ese" to "东东南", "se" to "东南", "sse" to "南东南", "s" to "南", "ssw" to "南西南",
            "sw" to "西南", "wsw" to "西西南", "w" to "西", "wnw" to "西西北", "nw" to "西北",
            "nnw" to "北西北", "none" to "无风向", "vrb" to "不定")
        val directionCode = direction.string("compass")
        val fromDegrees = if (directionCode in listOf("none", "vrb")) null else
            direction.number("degree")?.takeIf { it in 0.0..359.0 }
        val directionLabel = compass[directionCode] ?: fromDegrees?.let { String.format(Locale.US, "%.1f°", it) } ?: "—"
        metrics += EnvironmentMetric("windDirection", "风向", directionLabel, "",
            DataSource.REGIONAL_API, "区域风的来向，非手机指南针朝向。罗盘固定上北下南，亮点标出来风方位，箭头由亮点吹向中心；不会随手机转动。",
            windFromDegrees = fromDegrees)
        add("uv", "紫外线指数", weather.number("uvIndex")?.takeIf { it in 0.0..15.0 }, "UVI",
            "来自和风实时天气服务，没有使用手机光照强度推算紫外线。")
        metrics += EnvironmentMetric("altitude", "地形海拔", "—", "m", DataSource.ESTIMATED,
            "尚未接入独立地形海拔服务，不使用定位高度或气压估计值冒充准确地形海拔。")
        val notices = listOfNotNull(airWarning,
            if (air != null && index == null) "该区域未返回中国标准 AQI，指数留空。" else null,
            if (area.isMock) "当前位置由系统标记为模拟位置。" else null,
            "显示本次获取时间；此接口未提供独立观测时间。")
        val attributions = (weather.obj("metadata").array("attributions") + air?.obj("metadata")?.array("attributions").orEmpty())
            .filter { it.isJsonPrimitive }.map { it.asString }.distinct()
        // Air quality is fetched independently. A partial response must never borrow the weather time.
        val timedMetrics = metrics.map { metric ->
            val acquiredAt = when {
                metric.source != DataSource.REGIONAL_API || metric.value == "—" -> null
                metric.id in setOf("aqi", "pm25", "pm10") -> airFetchedAt.takeIf { air != null }
                else -> weatherFetchedAt
            }
            metric.copy(fetchedAtMillis = acquiredAt?.toEpochMilli())
        }
        return EnvironmentSnapshot(area.label, fetchedAt, timedMetrics, attributions = attributions, notices = notices, isDemo = false)
    }
}

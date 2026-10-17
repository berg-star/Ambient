package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.sky.*
import com.google.gson.JsonObject
import java.time.Clock
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

class QWeatherAstronomyService(private val client: QWeatherClient, private val clock: Clock = Clock.systemUTC()) : AstronomyDataService {
    override suspend fun sun(area: EnvironmentArea, date: String): SunTimes {
        val json = checked(client.sun(area, date))
        fun time(key: String): String? = json.string(key).takeIf {
            astronomyTime(it) != null && runCatching { java.time.OffsetDateTime.parse(it).toLocalDate() }
                .getOrNull() == LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
        }
        return SunTimes(time("sunrise"), time("sunset"), clock.millis(), attribution(json))
    }
    override suspend fun moon(area: EnvironmentArea, date: String): MoonDay {
        val json = checked(client.moon(area, date))
        val day = LocalDate.parse(date, DateTimeFormatter.BASIC_ISO_DATE)
        val phases = json.array("moonPhase").filter { it.isJsonObject }.mapNotNull {
            val obj = it.asJsonObject
            val time = obj.string("fxTime")
            if (runCatching { java.time.OffsetDateTime.parse(time).toLocalDate() }.getOrNull() != day) return@mapNotNull null
            MoonPhase(time, obj.string("name").take(40), obj.number("illumination")?.takeIf { v -> v in 0.0..100.0 })
        }.take(25)
        return MoonDay(phases, clock.millis(), attribution(json))
    }
    override suspend fun solar(area: EnvironmentArea, at: Instant, zone: ZoneId): SolarPosition {
        val sampled = at.truncatedTo(ChronoUnit.MINUTES)
        val local = sampled.atZone(zone)
        val offset = local.offset.totalSeconds / 60
        val tz = (if (offset < 0) "-" else "") + String.format(Locale.US, "%02d%02d", kotlin.math.abs(offset) / 60, kotlin.math.abs(offset) % 60)
        val height = positioningHeight(area)
        val json = checked(client.solar(area, local.toLocalDate().format(DateTimeFormatter.BASIC_ISO_DATE),
            local.format(DateTimeFormatter.ofPattern("HHmm", Locale.US)), tz, height?.toInt() ?: 0))
        return SolarPosition(json.number("solarElevationAngle")?.takeIf { it in -90.0..90.0 },
            json.number("solarAzimuthAngle")?.takeIf { it >= 0.0 && it < 360.0 }, sampled.toEpochMilli(),
            clock.millis(), attribution(json), height?.toInt() ?: 0, height != null)
    }
    private fun checked(json: JsonObject): JsonObject {
        val code = json.string("code")
        if (code != "200") throw EnvironmentException(code.toIntOrNull()?.let { QWeatherClient.httpError(it) }
            ?: "天文接口返回异常状态。")
        return json
    }
    private fun attribution(json: JsonObject): List<String> = listOf("sources", "license").flatMap { key ->
        json.obj("refer").array(key).filter { it.isJsonPrimitive }.map { it.asString.take(300) }
    }.distinct().take(12)
}

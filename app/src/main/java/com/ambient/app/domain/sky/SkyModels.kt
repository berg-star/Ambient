package com.ambient.app.domain.sky

import com.ambient.app.domain.environment.EnvironmentArea
import java.time.Duration
import java.time.Instant
import java.time.OffsetDateTime
import java.time.ZoneId

/** All values here are astronomy calculations, never SensorManager measurements. */
data class SunTimes(val sunrise: String?, val sunset: String?, val fetchedAt: Long,
                    val attributions: List<String> = emptyList())
data class MoonPhase(val time: String, val name: String, val illumination: Double?)
data class MoonDay(val phases: List<MoonPhase>, val fetchedAt: Long,
                   val attributions: List<String> = emptyList())
data class SolarPosition(val elevation: Double?, val azimuth: Double?, val calculatedFor: Long,
                         val fetchedAt: Long, val attributions: List<String> = emptyList(),
                         val referenceHeightMeters: Int = 0, val usedPositioningHeight: Boolean = false)
data class SkySnapshot(val area: EnvironmentArea, val date: String, val zoneId: String,
                       val deviceZoneFallback: Boolean, val sun: SunTimes? = null,
                       val moon: MoonDay? = null, val solar: SolarPosition? = null)
data class SkyResult(val snapshot: SkySnapshot?, val messages: List<String> = emptyList(),
    val isCached: Boolean = false,
    /** A partial refresh can retain an old part beside newly fetched calculations. */
    val sunCached: Boolean = isCached && snapshot?.sun != null,
    val moonCached: Boolean = isCached && snapshot?.moon != null,
    val solarCached: Boolean = isCached && snapshot?.solar != null)
fun interface SkyRepository { suspend fun loadSky(force: Boolean): SkyResult }

fun positioningHeight(area: EnvironmentArea): Double? = area.positioningHeightMeters?.takeIf {
    area.fromDevice && it.isFinite() && it in -500.0..10000.0
}
interface AstronomyDataService {
    suspend fun sun(area: EnvironmentArea, date: String): SunTimes
    suspend fun moon(area: EnvironmentArea, date: String): MoonDay
    suspend fun solar(area: EnvironmentArea, at: Instant, zone: ZoneId): SolarPosition
}

fun astronomyTime(value: String?): Instant? = value?.let { runCatching { OffsetDateTime.parse(it).toInstant() }.getOrNull() }

data class Daylight(val duration: Duration?, val progress: Float?, val headline: String, val remaining: Duration?)

/** The arc is elapsed daylight, not solar elevation, actual sunshine, or a measured sun path. */
fun daylight(snapshot: SkySnapshot, now: Instant): Daylight {
    if (now.atZone(ZoneId.of(snapshot.zoneId)).toLocalDate().toString() != snapshot.date)
        return Daylight(null, null, "日期已变化，请刷新", null)
    val rise = astronomyTime(snapshot.sun?.sunrise)
    val set = astronomyTime(snapshot.sun?.sunset)
    if (rise == null || set == null || !set.isAfter(rise))
        return Daylight(null, null, "日出日落不完整，无法计算白昼", null)
    val length = Duration.between(rise, set)
    return when {
        now.isBefore(rise) -> Daylight(length, 0f, "距离日出", Duration.between(now, rise))
        now.isBefore(set) -> Daylight(length,
            (Duration.between(rise, now).toMillis().toDouble() / length.toMillis()).toFloat(),
            "距离日落", Duration.between(now, set))
        else -> Daylight(length, 1f, "今日日照时段已结束", null)
    }
}

/** Never select an hour from tomorrow or fill a missing phase with a fabricated value. */
fun currentMoon(snapshot: SkySnapshot, now: Instant): MoonPhase? {
    val zone = ZoneId.of(snapshot.zoneId)
    if (now.atZone(zone).toLocalDate().toString() != snapshot.date) return null
    return snapshot.moon?.phases?.filter {
        val time = astronomyTime(it.time)
        time != null && time <= now && time.atZone(zone).toLocalDate().toString() == snapshot.date &&
            Duration.between(time, now).seconds in 0..3599
    }?.maxByOrNull { astronomyTime(it.time)!! }
}

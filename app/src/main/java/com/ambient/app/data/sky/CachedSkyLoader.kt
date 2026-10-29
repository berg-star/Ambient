package com.ambient.app.data.sky

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.sky.*
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Caller serializes access and persists the result. No scheduler, positioning, or hidden retries. */
class CachedSkyLoader(private val service: AstronomyDataService, private val clock: Clock = Clock.systemUTC(),
                      private val deviceZone: () -> ZoneId = { ZoneId.systemDefault() }) {
    private var lastAttempt: Pair<String, Instant>? = null

    suspend fun load(area: EnvironmentArea, cached: SkySnapshot?, force: Boolean): SkyResult {
        val now = clock.instant()
        val knownZone = area.timeZone?.let { runCatching { ZoneId.of(it) }.getOrNull() }
        val zone = knownZone ?: deviceZone()
        val date = now.atZone(zone).toLocalDate().toString()
        val old = cached?.takeIf { it.area == area && it.zoneId == zone.id && it.date == date }
        var snapshot = old ?: SkySnapshot(area, date, zone.id, knownZone == null)
        fun fresh(at: Long, seconds: Long) = Duration.between(Instant.ofEpochMilli(at), now).seconds in 0 until seconds
        if (!force && snapshot.sun?.let { fresh(it.fetchedAt, 172800) } == true &&
            snapshot.moon?.let { fresh(it.fetchedAt, 172800) } == true &&
            snapshot.solar?.let { fresh(it.fetchedAt, 600) } == true) return SkyResult(snapshot, isCached = true)
        val key = "$area|$date|${zone.id}"
        val previous = lastAttempt?.takeIf { it.first == key }?.second
        if (previous != null && Duration.between(previous, now).seconds in 0..29)
            return SkyResult(old, listOf("请间隔 30 秒再刷新日光与天空。"), isCached = old != null)
        lastAttempt = key to now
        val messages = mutableListOf<String>()
        var sunFetched = false
        var moonFetched = false
        var solarFetched = false
        suspend fun <T> part(label: String, work: suspend () -> T): T? = try {
            work()
        } catch (e: CancellationException) { throw e }
        catch (e: EnvironmentException) { messages += "$label：${e.userMessage}"; null }
        catch (_: Exception) { messages += "$label：数据读取失败，请稍后重试。"; null }
        val queryDate = date.replace("-", "")
        if (force || snapshot.sun?.let { fresh(it.fetchedAt, 172800) } != true) snapshot = snapshot.copy(
            sun = part("日出日落") { service.sun(area, queryDate).also { sunFetched = true } } ?: snapshot.sun)
        if (force || snapshot.moon?.let { fresh(it.fetchedAt, 172800) } != true) snapshot = snapshot.copy(
            moon = part("月相") { service.moon(area, queryDate).also { moonFetched = true } } ?: snapshot.moon)
        if (force || snapshot.solar?.let { fresh(it.fetchedAt, 600) } != true) snapshot = snapshot.copy(
            solar = part("太阳角度") { service.solar(area, now, zone).also { solarFetched = true } } ?: snapshot.solar)
        return SkyResult(snapshot.takeIf { it.sun != null || it.moon != null || it.solar != null },
            messages, isCached = !sunFetched && !moonFetched && !solarFetched && old != null,
            sunCached = snapshot.sun != null && !sunFetched,
            moonCached = snapshot.moon != null && !moonFetched,
            solarCached = snapshot.solar != null && !solarFetched)
    }
}

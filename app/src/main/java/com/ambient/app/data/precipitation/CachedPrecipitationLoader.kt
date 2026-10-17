package com.ambient.app.data.precipitation

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.precipitation.*
import kotlinx.coroutines.CancellationException
import java.time.Clock
import java.time.Instant

/** Called under the repository mutex. At most two requests, no scheduler or automatic retry. */
class CachedPrecipitationLoader(private val service: PrecipitationDataService,
                                private val clock: Clock = Clock.systemUTC()) {
    private var lastAttempt: Pair<EnvironmentArea, Long>? = null
    suspend fun load(area: EnvironmentArea, cached: PrecipitationSnapshot?, force: Boolean): PrecipitationResult {
        val now = clock.instant()
        val old = cached?.takeIf { it.area == area }
        fun fresh(f: PrecipitationForecast?, r: ForecastRange) = f != null &&
            !forecastStale(f, r, now) && forecastPoints(f, r, now).isNotEmpty()
        if (!force && fresh(old?.minutely, ForecastRange.TWO_HOURS) && fresh(old?.hourly, ForecastRange.THREE_DAYS))
            return PrecipitationResult(old, isCached = true)
        val age = lastAttempt?.takeIf { it.first == area }?.let { now.toEpochMilli() - it.second }
        if (age != null && age in 0 until 30_000)
            return PrecipitationResult(old, listOf("请间隔 30 秒再刷新降水预报。"), old != null)
        lastAttempt = area to now.toEpochMilli()
        var snapshot = old ?: PrecipitationSnapshot(area)
        val messages = mutableListOf<String>()
        var fetched = false
        suspend fun part(label: String, block: suspend () -> PrecipitationForecast): PrecipitationForecast? = try {
            block().also { fetched = true }
        } catch (e: CancellationException) { throw e }
        catch (e: EnvironmentException) { messages += "$label：${e.userMessage}"; null }
        catch (_: Exception) { messages += "$label：数据读取失败，请稍后重试。"; null }
        if (force || !fresh(snapshot.minutely, ForecastRange.TWO_HOURS)) snapshot = snapshot.copy(
            minutely = part("两小时降水") { service.minutely(area) } ?: snapshot.minutely)
        if (force || !fresh(snapshot.hourly, ForecastRange.THREE_DAYS)) snapshot = snapshot.copy(
            hourly = part("逐小时降水") { service.hourly(area) } ?: snapshot.hourly)
        return PrecipitationResult(snapshot.takeIf { it.minutely != null || it.hourly != null }, messages,
            isCached = !fetched && old != null)
    }
}

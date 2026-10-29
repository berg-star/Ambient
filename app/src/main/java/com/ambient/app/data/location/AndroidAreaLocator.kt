package com.ambient.app.data.location

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.os.SystemClock
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull

/** User-triggered single fix. Optional WGS84 height is a reference parameter, never terrain elevation. */
class AndroidAreaLocator(context: Context) {
    private val app = context.applicationContext
    private val manager = app.getSystemService(LocationManager::class.java)

    @SuppressLint("MissingPermission")
    suspend fun locate(): EnvironmentArea {
        val fine = app.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (!fine && !coarse) throw EnvironmentException("定位权限未授予，可使用城市搜索。")
        val location = withTimeoutOrNull(30000) {
            callbackFlow {
                val canTryGps = fine && runCatching { manager.isProviderEnabled(LocationManager.GPS_PROVIDER) }.getOrDefault(false)
                var fallback: Location? = null
                var heightGrace: Job? = null
                fun accept(location: Location) {
                    if (!usable(location)) return
                    if (!canTryGps || usableHeight(location)) { trySend(location); return }
                    if (fallback == null || location.accuracy < fallback!!.accuracy) fallback = location
                    // A quick network fix often omits height. Give GNSS a bounded chance, then keep the usable fix.
                    if (heightGrace?.isActive != true) heightGrace = launch {
                        delay(5000)
                        fallback?.takeIf(::usable)?.let { trySend(it) }
                    }
                }
                val listener = object : LocationListener {
                    override fun onLocationChanged(location: Location) { accept(location) }
                    override fun onProviderEnabled(provider: String) = Unit
                    override fun onProviderDisabled(provider: String) = Unit
                    @Deprecated("Legacy platform callback")
                    override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
                }
                var count = 0
                try {
                    for (provider in listOf(LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                        if (provider == LocationManager.GPS_PROVIDER && !fine) continue
                        if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
                        runCatching {
                            manager.requestLocationUpdates(provider, 1000L, 0f, listener, Looper.getMainLooper())
                            count++
                            manager.getLastKnownLocation(provider)?.let(::accept)
                        }
                    }
                    if (count == 0) close(EnvironmentException("系统定位不可用，请开启定位服务，或使用城市搜索。"))
                    awaitClose { heightGrace?.cancel(); manager.removeUpdates(listener) }
                } catch (e: Exception) { manager.removeUpdates(listener); throw e }
            }.first()
        } ?: throw EnvironmentException("定位超时。可到室外重试，或手动选择城市；未替换原区域。")
        return areaFromLocation(location)
    }

    private fun usableHeight(location: Location) = location.hasAltitude() && location.altitude.isFinite() && location.altitude in -500.0..10000.0

    private fun usable(location: Location): Boolean {
        val age = SystemClock.elapsedRealtimeNanos() - location.elapsedRealtimeNanos
        return age in 0..120_000_000_000L && location.hasAccuracy() && location.accuracy.isFinite() &&
            location.accuracy in 0f..5000f && location.latitude.isFinite() && location.latitude in -90.0..90.0 &&
            location.longitude.isFinite() && location.longitude in -180.0..180.0
    }
}

/** Android's missing-height default is 0; hasAltitude is essential before accepting even a real zero. */
internal fun areaFromLocation(location: Location): EnvironmentArea {
    val mock = if (Build.VERSION.SDK_INT >= 31) location.isMock else @Suppress("DEPRECATION") location.isFromMockProvider
    return EnvironmentArea("当前位置 · ${if (mock) "模拟定位" else if (location.provider == "gps") "卫星定位" else "网络定位"}",
        location.latitude, location.longitude, fromDevice = true, isMock = mock,
        positioningHeightMeters = location.altitude.takeIf { location.hasAltitude() && it.isFinite() && it in -500.0..10000.0 },
        verticalAccuracyMeters = location.verticalAccuracyMeters.toDouble().takeIf {
            location.hasVerticalAccuracy() && it.isFinite() && it >= 0.0
        }, positioningHeightAtMillis = location.time.takeIf { location.hasAltitude() && it > 0 },
        locatedAtMillis = location.time.takeIf { it > 0 })
}

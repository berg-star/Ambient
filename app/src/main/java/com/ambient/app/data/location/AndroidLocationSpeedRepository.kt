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
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.ambient.app.domain.location.SpeedFix
import com.ambient.app.domain.location.SpeedRepository
import com.ambient.app.domain.location.SpeedSamplePolicy
import com.ambient.app.domain.location.SpeedState
import com.ambient.app.domain.location.SpeedStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground, opt-in GNSS speed. No fused Google service, network, route, or background work. */
class AndroidLocationSpeedRepository(context: Context) : SpeedRepository {
    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(LocationManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val mutableState = MutableStateFlow(SpeedState())
    override val state = mutableState.asStateFlow()
    private var requested = false
    private var listener: LocationListener? = null
    private var generation = 0L
    private var latestFix: SpeedFix? = null

    // One bounded check per second also detects permission revocation or a disabled provider.
    private val freshnessCheck = object : Runnable {
        override fun run() {
            if (requested) reconcile()
        }
    }

    override fun start() {
        requested = true
        reconcile()
    }

    override fun stop() {
        requested = false
        detach()
        latestFix = null
        mutableState.value = SpeedState()
    }

    override fun refreshAccess() {
        if (requested) reconcile()
    }

    private fun accessStatus(): SpeedStatus? {
        val fine = appContext.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (fine) return null
        val coarse = appContext.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        return if (coarse) SpeedStatus.PRECISE_PERMISSION_REQUIRED else SpeedStatus.PERMISSION_REQUIRED
    }

    @SuppressLint("MissingPermission") // Runtime permission is checked immediately before registration.
    private fun reconcile() {
        handler.removeCallbacks(freshnessCheck)
        if (!requested) return
        accessStatus()?.let {
            detach()
            latestFix = null
            mutableState.value = SpeedState(it)
            return
        }
        val locationManager = manager
        if (locationManager == null) {
            fail(SpeedStatus.GPS_UNAVAILABLE)
            return
        }
        try {
            if (!locationManager.allProviders.contains(LocationManager.GPS_PROVIDER)) {
                fail(SpeedStatus.GPS_UNAVAILABLE)
                return
            }
            if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                fail(SpeedStatus.GPS_DISABLED)
                handler.postDelayed(freshnessCheck, CHECK_INTERVAL_MILLIS)
                return
            }
            if (listener == null) {
                latestFix = null
                val session = ++generation
                val nextListener = createListener(session)
                listener = nextListener
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    CHECK_INTERVAL_MILLIS,
                    0f,
                    nextListener,
                    Looper.getMainLooper(),
                )
                mutableState.value = SpeedState(SpeedStatus.WAITING_FOR_FIX, isListening = true)
            } else {
                latestFix?.let { publish(it) }
            }
            handler.postDelayed(freshnessCheck, CHECK_INTERVAL_MILLIS)
        } catch (_: SecurityException) {
            fail(accessStatus() ?: SpeedStatus.PERMISSION_REQUIRED)
        } catch (_: IllegalArgumentException) {
            fail(SpeedStatus.GPS_UNAVAILABLE)
        } catch (_: IllegalStateException) {
            fail(SpeedStatus.ERROR, "暂时无法启动系统定位，请稍后重新开启")
        }
    }

    private fun createListener(session: Long): LocationListener = object : LocationListener {
        override fun onLocationChanged(location: Location) {
            if (!requested || listener == null || session != generation) return
            if (accessStatus() != null) {
                reconcile()
                return
            }
            // Optional fields are used only when Android explicitly says they are present.
            val fix = SpeedFix(
                hasSpeed = location.hasSpeed(),
                metersPerSecond = if (location.hasSpeed()) location.speed else Float.NaN,
                accuracyMetersPerSecond = if (location.hasSpeedAccuracy()) {
                    location.speedAccuracyMetersPerSecond
                } else null,
                elapsedRealtimeNanos = location.elapsedRealtimeNanos,
                isMock = isMock(location),
            )
            val now = SystemClock.elapsedRealtimeNanos()
            if (fix.elapsedRealtimeNanos <= 0L || fix.elapsedRealtimeNanos > now) {
                // An invalid future timestamp must not poison ordering of all subsequent fixes.
                latestFix = null
                mutableState.value = SpeedSamplePolicy.evaluate(fix, now).copy(isListening = true)
                return
            }
            // Ignore out-of-order older fixes so they cannot replace a newer current observation.
            if (latestFix?.let { fix.elapsedRealtimeNanos < it.elapsedRealtimeNanos } == true) return
            latestFix = fix
            publish(fix)
        }

        override fun onProviderEnabled(provider: String) {
            if (requested && session == generation) reconcile()
        }

        override fun onProviderDisabled(provider: String) {
            if (requested && session == generation) {
                fail(SpeedStatus.GPS_DISABLED)
                handler.postDelayed(freshnessCheck, CHECK_INTERVAL_MILLIS)
            }
        }

        @Deprecated("Required only for compatibility with Android 8–10")
        override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
    }

    private fun publish(fix: SpeedFix) {
        mutableState.value = SpeedSamplePolicy.evaluate(fix, SystemClock.elapsedRealtimeNanos())
            .copy(isListening = listener != null)
    }

    private fun fail(status: SpeedStatus, message: String? = null) {
        detach()
        latestFix = null
        mutableState.value = SpeedState(status, message = message)
    }

    private fun detach() {
        handler.removeCallbacks(freshnessCheck)
        generation++
        val previousListener = listener
        listener = null
        if (previousListener != null) {
            try {
                manager?.removeUpdates(previousListener)
            } catch (_: SecurityException) {
                // Permission can be revoked while this page is visible.
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun isMock(location: Location): Boolean =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) location.isMock else location.isFromMockProvider

    private companion object {
        const val CHECK_INTERVAL_MILLIS = 1_000L
    }
}

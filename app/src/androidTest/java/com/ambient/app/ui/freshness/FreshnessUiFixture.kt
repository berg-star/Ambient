package com.ambient.app.ui.freshness

import android.os.Build
import android.provider.Settings
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.data.environment.qweather.QWeatherCredentials
import com.ambient.app.data.environment.qweather.SecureWeatherStore
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.EnvironmentPreferences
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.google.gson.Gson
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Base64

/** Opt-in synthetic fixture, test APK only. Requires the dedicated API 31 emulator offline. */
@RunWith(AndroidJUnit4::class)
class FreshnessUiFixture {
    @Test fun manage() {
        val mode = InstrumentationRegistry.getArguments().getString("freshnessFixture") ?: return
        check(mode in setOf("recent-manual", "old-location", "legacy", "missing-air", "future", "stale", "restore"))
        // Check before creating directories, reading credentials, or changing any application state.
        val fingerprint = Build.FINGERPRINT.lowercase()
        val model = Build.MODEL.lowercase()
        check(Build.VERSION.SDK_INT == 31 &&
            (fingerprint.startsWith("generic") || "emulator" in fingerprint || "sdk_gphone" in fingerprint) &&
            ("sdk" in model || "emulator" in model) &&
            Build.HARDWARE in setOf("ranchu", "goldfish")) {
            "Freshness fixtures require an API 31 Android emulator; physical phones are forbidden."
        }
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "weather")
        val backup = File(context.noBackupFilesDir, "freshness-ui-test-backup")
        val keys = listOf("credentials", "preferences", "snapshot", "sky", "precipitation")
        // Preserve AtomicFile sidecars as well, without decrypting or logging the original files.
        val files = keys.flatMap { listOf(it, "$it.bak", "$it.new") }
        val complete = File(backup, "complete")
        if (mode == "restore") {
            if (backup.exists()) {
                check(complete.exists()) { "Incomplete backup; original files were not changed." }
                check(directory.isDirectory || directory.mkdirs())
                files.forEach { name ->
                    val saved = File(backup, name)
                    val target = File(directory, name)
                    if (saved.exists()) saved.copyTo(target, overwrite = true)
                    else check(!target.exists() || target.delete())
                }
                check(backup.deleteRecursively())
            }
            return
        }
        check(Settings.Global.getInt(context.contentResolver, "wifi_on", 1) == 0 &&
            Settings.Global.getInt(context.contentResolver, "mobile_data", 1) == 0) {
            "Disable Wi-Fi and mobile data on the dedicated emulator before using offline fixtures."
        }
        if (!backup.exists()) {
            check(backup.mkdirs())
            files.forEach { name -> File(directory, name).takeIf { it.exists() }?.copyTo(File(backup, name)) }
            complete.writeText("Synthetic freshness fixture backup complete")
        }
        check(complete.exists()) { "Incomplete backup; refusing to change application state." }

        val now = Instant.now()
        val old = now.minusSeconds(7200)
        val weatherAt = when (mode) {
            "stale" -> old
            "future" -> now.plusSeconds(3600)
            "recent-manual", "legacy" -> now.minusSeconds(120)
            else -> now.minusSeconds(30)
        }
        val airAt = when (mode) {
            "stale", "old-location" -> old
            else -> now.minusSeconds(300)
        }
        val deviceArea = mode in setOf("old-location", "legacy")
        val area = EnvironmentArea("离线合成测试 · 非实际天气与定位", 39.92, 116.41,
            fromDevice = deviceArea, isMock = deviceArea, timeZone = "Asia/Shanghai",
            positioningHeightMeters = if (mode == "old-location") 123.0 else null,
            verticalAccuracyMeters = if (mode == "old-location") 15.0 else null,
            positioningHeightAtMillis = if (mode == "old-location") old.toEpochMilli() else null,
            locatedAtMillis = if (mode == "old-location") old.toEpochMilli() else null)
        fun metric(id: String, label: String, value: String, unit: String, air: Boolean = false) =
            EnvironmentMetric(id, label, value, unit, DataSource.REGIONAL_API,
                "离线合成界面测试；此数值并非真实天气或手机实测。",
                fetchedAtMillis = if (mode == "legacy" || value == "—") null else
                    (if (air) airAt else weatherAt).toEpochMilli())
        val snapshot = EnvironmentSnapshot(area.label,
            // Future per-metric timestamps are isolated from the normal recent cache-loading path.
            if (mode == "future") now.minusSeconds(10) else weatherAt,
            listOf(
                metric("temperature", "室外温度", "20.0", "°C"),
                metric("humidity", "相对湿度", "50", "%"),
                metric("pressure", "海平面气压", "1013.0", "hPa"),
                metric("feelsLike", "体感温度", "21.5", "°C"),
                metric("aqi", "空气质量 · 中国", if (mode == "missing-air") "—" else "45", "AQI", air = true),
                metric("pm25", "PM2.5", if (mode == "missing-air") "—" else "12.0", "μg/m³", air = true),
                metric("pm10", "PM10", if (mode == "missing-air") "—" else "20.0", "μg/m³", air = true),
            ),
            notices = listOfNotNull("离线合成测试数据，非实际天气与定位；未提供观测时间。",
                "空气质量未更新：合成失败状态。".takeIf { mode == "missing-air" }),
            isDemo = false)
        // Public, reproducible fixture key: never a production or user's signing key.
        val seed = EdDSAPrivateKeySpec(ByteArray(32) { it.toByte() }, EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
        val pem = "-----BEGIN PRIVATE " + "KEY-----\n" +
            Base64.getEncoder().encodeToString(EdDSAPrivateKey(seed).encoded) + "\n-----END PRIVATE " + "KEY-----"
        val store = SecureWeatherStore(context)
        keys.forEach(store::delete)
        store.write("credentials", QWeatherCredentials("Q12345ABCD", "PROJECT01", "CRED00001", "test.qweatherapi.com", pem).encode())
        store.write("preferences", Gson().toJson(EnvironmentPreferences(true, true, area)))
        store.write("snapshot", SnapshotCodec.encode(snapshot))
    }
}

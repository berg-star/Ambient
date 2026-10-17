package com.ambient.app.ui.sky

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.data.environment.qweather.QWeatherCredentials
import com.ambient.app.data.environment.qweather.SecureWeatherStore
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentPreferences
import com.ambient.app.domain.sky.*
import com.google.gson.Gson
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.util.Base64

/** Opt-in offline UI fixture. Never included in the main APK and never uses a user's credentials. */
@RunWith(AndroidJUnit4::class)
class SkyUiFixture {
    @Test fun manage() {
        val args = InstrumentationRegistry.getArguments()
        val mode = args.getString("skyFixture") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir, "weather")
        val backup = File(context.noBackupFilesDir, "sky-ui-test-backup")
        val keys = listOf("credentials", "preferences", "snapshot", "sky")
        if (mode == "restore") {
            if (backup.exists()) {
                keys.forEach { key ->
                    val saved = File(backup, key)
                    if (saved.exists()) saved.copyTo(File(directory, key), overwrite = true)
                    else File(directory, key).delete()
                }
                backup.deleteRecursively()
            }
            return
        }
        check(mode in listOf("reference", "height", "missing"))
        if (!backup.exists()) {
            backup.mkdirs()
            keys.forEach { key -> File(directory, key).takeIf { it.exists() }?.copyTo(File(backup, key)) }
        }
        val now = Instant.now()
        val zone = ZoneId.of("Asia/Shanghai")
        val date = now.atZone(zone).toLocalDate()
        val area = EnvironmentArea("界面测试 · 非实际天文数据", 39.92, 116.41,
            fromDevice = mode == "height", isMock = mode == "height", timeZone = zone.id,
            positioningHeightMeters = if (mode == "height") 123.0 else null,
            verticalAccuracyMeters = if (mode == "height") 15.0 else null)
        val seed = EdDSAPrivateKeySpec(ByteArray(32) { it.toByte() }, EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
        val pem = "-----BEGIN PRIVATE " + "KEY-----\n" + Base64.getEncoder().encodeToString(EdDSAPrivateKey(seed).encoded) + "\n-----END PRIVATE " + "KEY-----"
        val credentials = QWeatherCredentials("Q12345ABCD", "PROJECT01", "CRED00001", "test.qweatherapi.com", pem)
        val snapshot = SkySnapshot(area, date.toString(), zone.id, false,
            SunTimes(if (mode == "missing") null else date.atTime(6, 0).atZone(zone).toOffsetDateTime().toString(),
                if (mode == "missing") null else date.atTime(18, 0).atZone(zone).toOffsetDateTime().toString(), now.toEpochMilli()),
            MoonDay(if (mode == "missing") emptyList() else listOf(MoonPhase(
                now.atZone(zone).truncatedTo(ChronoUnit.HOURS).toOffsetDateTime().toString(), "测试月相", 42.0)), now.toEpochMilli()),
            SolarPosition(if (mode == "missing") null else 42.5, if (mode == "missing") null else 185.9,
                now.truncatedTo(ChronoUnit.MINUTES).toEpochMilli(), now.toEpochMilli(),
                referenceHeightMeters = if (mode == "height") 123 else 0, usedPositioningHeight = mode == "height"))
        val store = SecureWeatherStore(context)
        store.write("credentials", credentials.encode())
        store.write("preferences", Gson().toJson(EnvironmentPreferences(true, true, area)))
        store.delete("snapshot")
        store.write("sky", Gson().toJson(snapshot))
    }
}

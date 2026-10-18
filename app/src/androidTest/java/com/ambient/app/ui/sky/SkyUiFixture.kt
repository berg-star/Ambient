package com.ambient.app.ui.sky

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.data.environment.qweather.QWeatherCredentials
import com.ambient.app.data.environment.qweather.SecureWeatherStore
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentPreferences
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.environment.EnvironmentMetric
import com.ambient.app.domain.environment.DataSource
import com.ambient.app.data.environment.SnapshotCodec
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
        if (mode == "details-verify-live") {
            val payload = SecureWeatherStore(context).read("snapshot") ?: error("No live weather cache")
            val weather = SnapshotCodec.decode(payload)
            check(!weather.isDemo && weather.areaLabel.contains("北京"))
            val details = com.ambient.app.domain.environment.EnvironmentDetails.metrics(weather)
            check(details.all { it.source == DataSource.REGIONAL_API && it.value != "—" && it.value.toDoubleOrNull()?.isFinite() == true })
            return
        }
        val directory = File(context.noBackupFilesDir, "weather")
        val backup = File(context.noBackupFilesDir, "sky-ui-test-backup")
        val keys = listOf("credentials", "preferences", "snapshot", "sky", "precipitation")
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
        check(mode in listOf("reference", "height", "missing", "overview-height", "overview-missing", "overview-manual", "overview-no-weather",
            "details-values", "details-missing", "details-legacy", "details-zero", "details-old", "details-no-weather", "details-demo"))
        if (!backup.exists()) {
            backup.mkdirs()
            keys.forEach { key -> File(directory, key).takeIf { it.exists() }?.copyTo(File(backup, key)) }
        }
        val now = Instant.now()
        val zone = ZoneId.of("Asia/Shanghai")
        val date = now.atZone(zone).toLocalDate()
        val hasHeight = mode in listOf("height", "overview-height", "overview-manual", "overview-no-weather")
        val device = mode in listOf("height", "overview-height", "overview-missing", "overview-no-weather")
        val area = EnvironmentArea("界面测试 · 非实际天文数据", 39.92, 116.41,
            fromDevice = device, isMock = device, timeZone = zone.id,
            positioningHeightMeters = if (hasHeight) 123.0 else null,
            verticalAccuracyMeters = if (hasHeight) 15.0 else null,
            positioningHeightAtMillis = if (hasHeight) now.minusSeconds(120).toEpochMilli() else null)
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
        if (mode.startsWith("details-")) {
            store.delete("sky"); store.delete("precipitation")
            if (mode == "details-demo") {
                store.delete("credentials"); store.write("preferences", Gson().toJson(EnvironmentPreferences())); return
            }
            if (mode == "details-no-weather") return
            val zero = mode == "details-zero"
            val json = com.google.gson.JsonParser.parseString("""{"metadata":{"attributions":["离线合成天气 · 非实际观测"]},"temperature":{"value":20,"unit":"°C"}}""").asJsonObject
            if (mode !in listOf("details-missing", "details-legacy")) {
                for ((key,value,unit) in listOf(Triple("feelsLike", if(zero) 0.0 else 22.4,"°C"),
                    Triple("dewPoint",if(zero) 0.0 else -2.3,"°C"),Triple("visibility",if(zero) 0.0 else 12340.0,"m"),
                    Triple("windGust",if(zero) 0.0 else 7.1,"m/s"))) {
                    json.add(key, com.google.gson.JsonObject().apply { addProperty("value",value); addProperty("unit",unit) })
                }
                json.addProperty("cloudCover",if(zero) 0.0 else 0.42)
            }
            val detailsArea=area.copy(label="离线界面测试 · 非实际天气")
            store.write("preferences",Gson().toJson(EnvironmentPreferences(true,true,detailsArea)))
            var weather=com.ambient.app.data.environment.qweather.QWeatherDecoder.decode(json,null,detailsArea,
                if(mode=="details-old") now.minusSeconds(3600) else now)
            if(mode=="details-legacy") weather=weather.copy(metrics=com.ambient.app.domain.environment.EnvironmentDetails.overviewMetrics(weather.metrics))
            store.write("snapshot",SnapshotCodec.encode(weather)); return
        }
        if (mode.startsWith("overview-") && mode != "overview-no-weather") store.write("snapshot", SnapshotCodec.encode(
            EnvironmentSnapshot(area.label, now, listOf(
                EnvironmentMetric("temperature", "室外温度", "20.0", "°C", DataSource.REGIONAL_API, "合成界面测试，非实际天气"),
                EnvironmentMetric("altitude", "地形海拔", "—", "m", DataSource.ESTIMATED, "未接入")),
                notices = listOf("离线测试夹具 · 非实际天气或定位数据"))))
        store.write("sky", Gson().toJson(snapshot))
    }
}

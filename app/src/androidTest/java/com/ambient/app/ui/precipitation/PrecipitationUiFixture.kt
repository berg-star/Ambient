package com.ambient.app.ui.precipitation

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.data.environment.qweather.QWeatherCredentials
import com.ambient.app.data.environment.qweather.SecureWeatherStore
import com.ambient.app.domain.environment.*
import com.ambient.app.domain.precipitation.*
import com.google.gson.Gson
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.Instant
import java.util.Base64

/** Explicit opt-in, isolated emulator only. Synthetic readings never enter the main APK. */
@RunWith(AndroidJUnit4::class)
class PrecipitationUiFixture {
    @Test fun manage() {
        val mode = InstrumentationRegistry.getArguments().getString("precipitationFixture") ?: return
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val directory = File(context.noBackupFilesDir,"weather").apply { mkdirs() }
        val backup = File(context.noBackupFilesDir,"precipitation-ui-test-backup")
        val keys = listOf("credentials","preferences","snapshot","sky","precipitation")
        if (mode == "restore") {
            if (backup.exists()) {
                keys.forEach { key ->
                    val saved=File(backup,key)
                    if (saved.exists()) saved.copyTo(File(directory,key),overwrite=true) else File(directory,key).delete()
                }
                backup.deleteRecursively()
            }
            return
        }
        check(mode in listOf("wet","dry","missing","expired","partial","unconfigured"))
        if (!backup.exists()) {
            backup.mkdirs()
            keys.forEach { key -> File(directory,key).takeIf { it.exists() }?.copyTo(File(backup,key)) }
        }
        val store=SecureWeatherStore(context)
        keys.forEach(store::delete)
        if (mode == "unconfigured") return
        val now=Instant.now()
        val at = now.toEpochMilli() - if (mode == "expired") 4 * 86_400_000L else 0L
        val area=EnvironmentArea("离线界面测试 · 非实际预报",39.92,116.41,timeZone="Asia/Shanghai")
        val seed=EdDSAPrivateKeySpec(ByteArray(32) { it.toByte() },EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
        val pem="-----BEGIN PRIVATE " + "KEY-----\n" + Base64.getEncoder().encodeToString(EdDSAPrivateKey(seed).encoded) + "\n-----END PRIVATE " + "KEY-----"
        store.write("credentials",QWeatherCredentials("Q12345ABCD","PROJECT01","CRED00001","test.qweatherapi.com",pem).encode())
        store.write("preferences",Gson().toJson(EnvironmentPreferences(true,true,area)))
        store.write("snapshot",SnapshotCodec.encode(EnvironmentSnapshot(area.label,now,listOf(
            EnvironmentMetric("temperature","室外温度","20.0","°C",DataSource.REGIONAL_API,"合成测试，非实际天气")))))
        fun points(count: Int, step: Long) = List(count) { i -> PrecipitationPoint(at/step*step + i*step,
            if (mode=="missing") null else if (mode=="dry") 0.0 else if(i%6==0) 0.15 else i%4*0.12,
            if (step==300_000L || mode=="missing") null else .35, if (i%5==1) "snow" else "rain") }
        store.write("precipitation",Gson().toJson(PrecipitationSnapshot(area,
            if (mode=="partial") null else PrecipitationForecast(points(24,300_000),at,at,
                if (mode=="dry") "预计无降水 · 测试预报" else "测试降水提示",listOf("合成数据 · 测试用途")),
            PrecipitationForecast(points(72,3_600_000),at,attributions=listOf("合成数据 · 测试用途")))))
    }
}

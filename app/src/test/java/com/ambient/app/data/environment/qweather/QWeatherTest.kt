package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.DataSource
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import net.i2p.crypto.eddsa.EdDSAEngine
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.EdDSAPublicKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64

class QWeatherTest {
    private val time = Instant.parse("2026-10-16T08:00:00Z")
    private val area = EnvironmentArea("测试区域", 39.91755, 116.41876)
    // Synthetic fixture, not a real observation or user credential.
    private val weather = """{"metadata":{"attributions":["https://example.org/attribution"]},"temperature":{"value":19.4,"unit":"°C"},"humidity":0.3,"pressure":{"value":1022.17,"unit":"hPa"},"wind":{"speed":{"value":3.49,"unit":"m/s"},"direction":{"degree":41,"compass":"ne"}},"uvIndex":0}"""
    private val air = """{"metadata":{"attributions":["https://example.org/air"]},"indexes":[{"code":"us-epa","aqiDisplay":"99"},{"code":"cn-mee","aqiDisplay":"50","category":"优"}],"pollutants":[{"code":"pm2p5","concentration":{"value":14,"unit":"μg/m³"}},{"code":"pm10","concentration":{"value":50,"unit":"μg/m3"}}]}"""
    private fun json(value: String) = JsonParser.parseString(value).asJsonObject

    private fun credentials(host: String = "test.qweatherapi.com"): QWeatherCredentials {
        val spec = EdDSAPrivateKeySpec(ByteArray(32) { it.toByte() }, EdDSANamedCurveTable.ED_25519_CURVE_SPEC)
        val encoded = Base64.getEncoder().encodeToString(EdDSAPrivateKey(spec).encoded)
        val pem = "-----BEGIN PRIVATE " + "KEY-----\n" + encoded + "\n-----END PRIVATE " + "KEY-----"
        return QWeatherCredentials("Q12345ABCD", "PROJECT01", "CRED00001", host, pem)
    }

    @Test fun jwtSignsCurrentClaimsAndRejectsTampering() {
        val c = credentials()
        c.validate()
        val jwt = QWeatherJwt(c, Clock.fixed(time, ZoneOffset.UTC)).create()
        assertFalse(jwt.contains('='))
        val parts = jwt.split('.')
        assertEquals(3, parts.size)
        val header = json(String(Base64.getUrlDecoder().decode(parts[0]), Charsets.UTF_8))
        val payload = json(String(Base64.getUrlDecoder().decode(parts[1]), Charsets.UTF_8))
        assertEquals("EdDSA", header.string("alg"))
        assertEquals("CRED00001", header.string("kid"))
        assertEquals("Q12345ABCD", payload.string("iss"))
        assertEquals("PROJECT01", payload.string("sub"))
        assertEquals(time.epochSecond - 30, payload.get("iat").asLong)
        assertEquals(time.epochSecond + 600, payload.get("exp").asLong)
        val key = c.decodeKey()
        val publicKey = EdDSAPublicKey(EdDSAPublicKeySpec(key.abyte, key.params))
        fun verify(input: String): Boolean = EdDSAEngine(MessageDigest.getInstance("SHA-512")).run {
            initVerify(publicKey); update(input.toByteArray(Charsets.UTF_8)); verify(Base64.getUrlDecoder().decode(parts[2]))
        }
        assertTrue(verify(parts.take(2).joinToString(".")))
        assertFalse(verify(parts.take(2).joinToString(".") + "tampered"))
    }

    @Test fun hostValidationPreventsCredentialExfiltration() {
        for (host in listOf("https://test.qweatherapi.com", "test.qweatherapi.com.evil.org", "localhost", "test.qweatherapi.com/path", "test.qweatherapi.com:443", "user@test.qweatherapi.com")) {
            assertThrows(IllegalArgumentException::class.java) { credentials(host).validate() }
        }
        credentials("test.region.qweatherapi.com").validate()
    }

    @Test fun convertsHumidityKeepsUnitsAndSelectsChineseAqiOnly() {
        val snapshot = QWeatherDecoder.decode(json(weather), json(air), area, time)
        val m = snapshot.metrics.associateBy { it.id }
        assertEquals("30", m.getValue("humidity").value)
        assertEquals("海平面气压", m.getValue("pressure").label)
        assertEquals("50", m.getValue("aqi").value)
        assertEquals("14.0", m.getValue("pm25").value)
        assertEquals("3.5", m.getValue("windSpeed").value)
        assertEquals("东北", m.getValue("windDirection").value)
        assertEquals("0.0", m.getValue("uv").value)
        assertEquals("—", m.getValue("altitude").value)
        assertFalse(snapshot.isDemo)
        assertNull(snapshot.observedAt)
        assertEquals(time, snapshot.updatedAt)
        assertTrue(snapshot.metrics.none { it.source == DataSource.DEMO || it.source == DataSource.DEVICE })
        assertEquals(2, snapshot.attributions.size)
    }

    @Test fun absentChineseAqiNeverFallsBackToUsIndex() {
        val data = json(air)
        data.getAsJsonArray("indexes").remove(1)
        val snapshot = QWeatherDecoder.decode(json(weather), data, area, time)
        assertEquals("—", snapshot.metrics.first { it.id == "aqi" }.value)
        assertTrue(snapshot.notices.any { it.contains("中国标准") })
    }

    @Test fun missingAndInvalidValuesStayMissingAndWrongUnitsAreNotRelabeled() {
        val data = json(weather)
        data.addProperty("humidity", 62)
        data.getAsJsonObject("wind").getAsJsonObject("speed").addProperty("unit", "km/h")
        data.remove("uvIndex")
        val snapshot = QWeatherDecoder.decode(data, null, area, time, "空气质量未更新")
        for (id in listOf("humidity", "windSpeed", "uv", "aqi", "pm25", "pm10"))
            assertEquals(id, "—", snapshot.metrics.first { it.id == id }.value)
        assertTrue(snapshot.notices.contains("空气质量未更新"))
    }

    @Test fun persistentCacheRoundTripsTimeSourcesNoticesAndAttribution() {
        val original = QWeatherDecoder.decode(json(weather), json(air), area, time)
        assertEquals(original, SnapshotCodec.decode(SnapshotCodec.encode(original)))
    }

    @Test fun retrofitRequestsUseDocumentedPathsAndCoordinatePrecision() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody(weather))
            server.enqueue(MockResponse().setBody(air))
            server.enqueue(MockResponse().setBody("""{"code":"200","location":[{"name":"测试城","adm1":"测试省","lat":"39.9","lon":"116.4"}]}"""))
            val client = QWeatherClient(server.url("/").toString(), OkHttpClient())
            client.weather(area); client.air(area)
            val found = client.cities("测试城")
            assertEquals(1, found.size)
            assertEquals("/weather/v1/current/39.92/116.42?lang=zh", server.takeRequest().path)
            assertEquals("/airquality/v1/current/39.92/116.42?lang=zh", server.takeRequest().path)
            val geo = server.takeRequest().requestUrl!!
            assertEquals("/geo/v2/city/lookup", geo.encodedPath)
            assertEquals("测试城", geo.queryParameter("location"))
        }
    }

    @Test fun airFailurePreservesWeatherAndAddsExplicitWarning() = runBlocking {
        MockWebServer().use { server ->
            server.dispatcher = object : okhttp3.mockwebserver.Dispatcher() {
                override fun dispatch(request: okhttp3.mockwebserver.RecordedRequest): MockResponse =
                    if (request.path!!.startsWith("/airquality")) MockResponse().setResponseCode(403)
                    else MockResponse().setBody(weather)
            }
            val client = QWeatherClient(server.url("/").toString(), OkHttpClient())
            val result = QWeatherDataService(client, area, Clock.fixed(time, ZoneOffset.UTC)).fetch()
            assertEquals("19.4", result.metrics.first { it.id == "temperature" }.value)
            assertEquals("—", result.metrics.first { it.id == "aqi" }.value)
            assertTrue(result.notices.any { it.contains("403") })
        }
    }

    @Test fun httpErrorsNeverEchoServerBodyOrRequestCredentials() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(401).setBody("sensitive-debug-response"))
            val client = QWeatherClient(server.url("/").toString(), OkHttpClient())
            try { client.weather(area); fail("Must fail") }
            catch (e: EnvironmentException) {
                assertTrue(e.userMessage.contains("401"))
                assertFalse(e.userMessage.contains("sensitive"))
            }
        }
    }
}

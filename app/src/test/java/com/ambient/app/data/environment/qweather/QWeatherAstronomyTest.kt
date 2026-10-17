package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QWeatherAstronomyTest {
    private val area = EnvironmentArea("测试", 39.91755, 116.41876)
    private val now = Instant.parse("2026-10-17T04:23:45Z")
    private val clock = Clock.fixed(now, ZoneOffset.UTC)
    private fun service(server: MockWebServer) = QWeatherAstronomyService(QWeatherClient(server.url("/").toString(), OkHttpClient()), clock)
    @Test fun sunriseRequestUsesLongitudeFirstAndKeepsOffsetTimesAndAttribution() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","sunrise":"2026-10-17T06:00+08:00","sunset":"2026-10-17T18:00+08:00","refer":{"sources":["fixture"],"license":["fixture-license"]}}"""))
            val result = service(server).sun(area, "20261017")
            val request = server.takeRequest().requestUrl!!
            assertEquals("/v7/astronomy/sun", request.encodedPath)
            assertEquals("116.42,39.92", request.queryParameter("location"))
            assertEquals("20261017", request.queryParameter("date"))
            assertEquals("2026-10-17T06:00+08:00", result.sunrise)
            assertEquals(listOf("fixture", "fixture-license"), result.attributions)
        }
    }
    @Test fun solarUsesDocumentedDateTimeOffsetAndExplicitZeroReference() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","solarElevationAngle":"42.5","solarAzimuthAngle":"185.9"}"""))
            val result = service(server).solar(area, now, ZoneId.of("Asia/Shanghai"))
            val request = server.takeRequest().requestUrl!!
            assertEquals("/v7/astronomy/solar-elevation-angle", request.encodedPath)
            assertEquals("20261017", request.queryParameter("date"))
            assertEquals("1223", request.queryParameter("time"))
            assertEquals("0800", request.queryParameter("tz"))
            assertEquals("0", request.queryParameter("alt"))
            assertEquals(Instant.parse("2026-10-17T04:23:00Z").toEpochMilli(), result.calculatedFor)
            assertFalse(result.usedPositioningHeight)
        }
    }
    @Test fun negativeHalfHourOffsetAndDeviceHeightAreNotLost() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","solarElevationAngle":"-2","solarAzimuthAngle":"270"}"""))
            val result = service(server).solar(area.copy(fromDevice = true, positioningHeightMeters = 123.8), now, ZoneOffset.ofHoursMinutes(-5, -30))
            val request = server.takeRequest().requestUrl!!
            assertEquals("-0530", request.queryParameter("tz"))
            assertEquals("123", request.queryParameter("alt"))
            assertTrue(result.usedPositioningHeight); assertEquals(123, result.referenceHeightMeters)
        }
    }
    @Test fun invalidAnglesAndMissingSunTimesStayMissing() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","solarElevationAngle":"NaN","solarAzimuthAngle":"360"}"""))
            val position = service(server).solar(area, now, ZoneOffset.UTC)
            assertNull(position.elevation); assertNull(position.azimuth)
            server.enqueue(MockResponse().setBody("""{"code":"200","sunrise":"","sunset":"2026-10-18T18:00+08:00"}"""))
            val times = service(server).sun(area, "20261017")
            assertNull(times.sunrise); assertNull(times.sunset)
        }
    }
    @Test fun moonSkipsWrongDatesAndDoesNotClampInvalidIllumination() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","moonPhase":[{"fxTime":"2026-10-17T12:00+08:00","name":"测试月相","illumination":"101"},{"fxTime":"2026-10-18T12:00+08:00","name":"错误日期","illumination":"50"}]}"""))
            val moon = service(server).moon(area, "20261017")
            assertEquals(1, moon.phases.size); assertNull(moon.phases.single().illumination)
            assertEquals("/v7/astronomy/moon", server.takeRequest().requestUrl!!.encodedPath)
        }
    }
    @Test fun businessPermissionErrorIsNotAcceptedAsEmptySuccessfulData() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"403"}"""))
            try { service(server).sun(area, "20261017"); fail() }
            catch (e: EnvironmentException) { assertTrue(e.userMessage.contains("403")) }
        }
    }
    @Test fun citySearchRetainsTimeZoneWithoutInventingAltitude() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","location":[{"name":"测试","lat":"30","lon":"120","tz":"Asia/Shanghai"}]}"""))
            val result = QWeatherClient(server.url("/").toString(), OkHttpClient()).cities("测试").single()
            assertEquals("Asia/Shanghai", result.timeZone); assertNull(result.positioningHeightMeters)
        }
    }
    @Test fun documentedSecurityRestrictionIsSpecificButRawErrorDetailsNeverReachUi() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"type":"https://dev.qweather.com/docs/resource/error-code/#security-restriction","detail":"sensitive-fixture-value"}}"""))
            try { service(server).sun(area, "20261017"); fail() }
            catch (e: EnvironmentException) {
                assertTrue(e.userMessage.contains("JWT 凭据")); assertFalse(e.userMessage.contains("sensitive"))
            }
        }
    }
    @Test fun creditErrorAndUntrustedProblemTypeDoNotMasqueradeAsAuthorizationDiagnosis() = runBlocking {
        MockWebServer().use { server ->
            for ((type, expected) in listOf("https://dev.qweather.com/docs/resource/error-code/#no-credit" to "额度不足",
                "https://untrusted.invalid/#no-credit" to "接口访问被拒绝")) {
                server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"type":"$type"}}"""))
                try { service(server).sun(area, "20261017"); fail() }
                catch (e: EnvironmentException) { assertTrue(e.userMessage.contains(expected)) }
            }
        }
    }
}

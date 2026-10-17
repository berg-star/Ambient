package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class QWeatherPrecipitationTest {
    private fun json(text: String) = JsonParser.parseString(text).asJsonObject
    private val time = "2026-10-17T12:00+08:00"
    private val point = """{"fxTime":"$time","precip":"0.15","type":"snow"}"""
    @Test fun minutelyRequestUsesLongitudeFirstAndFiveMinuteAmountNotIntensity() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"code":"200","updateTime":"$time","summary":"测试预报","minutely":[$point],"refer":{"sources":["source"],"license":["license"]}}"""))
            val service = QWeatherPrecipitationService(QWeatherClient(server.url("/").toString(), OkHttpClient()), Clock.fixed(Instant.EPOCH, ZoneOffset.UTC))
            val result = service.minutely(EnvironmentArea("test",39.91755,116.41876))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/v7/minutely/5m", url.encodedPath); assertEquals("116.42,39.92", url.queryParameter("location"))
            assertEquals(0.15, result.points.single().amountMm!!, 0.0); assertNull(result.points.single().probability)
            assertEquals("snow", result.points.single().type); assertEquals(0L, result.fetchedAt)
            assertEquals(Instant.parse("2026-10-17T04:00:00Z").toEpochMilli(), result.updatedAt)
            assertEquals(listOf("source", "license"), result.attributions)
        }
    }
    @Test fun hourlyRequestsSeventyTwoOnceAndDoesNotInventIssueTime() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setBody("""{"metadata":{"attributions":["source"]},"hours":[{"forecastTime":"$time","precipitation":{"amount":{"value":0.2,"unit":"mm"},"probability":0.35,"type":"rain","intensity":{"value":99}}}]}"""))
            val service = QWeatherPrecipitationService(QWeatherClient(server.url("/").toString(), OkHttpClient()))
            val result = service.hourly(EnvironmentArea("test",39.92,116.41))
            val url = server.takeRequest().requestUrl!!
            assertEquals("/weather/v1/hourly/39.92/116.41", url.encodedPath); assertEquals("72", url.queryParameter("hours"))
            assertEquals(0.2, result.points.single().amountMm!!, 0.0); assertEquals(0.35, result.points.single().probability!!, 0.0)
            assertNull(result.updatedAt); assertEquals(listOf("source"), result.attributions)
        }
    }
    @Test fun invalidOrMissingAmountsAreNotZero() {
        for (value in listOf("-1", "NaN", "infinity", "", "null")) {
            val result = QWeatherPrecipitationService.decodeMinutely(json("""{"code":"200","minutely":[{"fxTime":"$time","precip":"$value"}]}"""), 0)
            assertNull(result.points.single().amountMm)
        }
        assertEquals(0.0, QWeatherPrecipitationService.decodeMinutely(json("""{"code":"200","minutely":[{"fxTime":"$time","precip":"0"}]}"""), 0).points.single().amountMm!!, 0.0)
    }
    @Test fun unknownUnitsAndOutOfRangeProbabilityStayMissing() {
        val result = QWeatherPrecipitationService.decodeHourly(json("""{"hours":[{"forecastTime":"$time","precipitation":{"amount":{"value":1,"unit":"inch"},"probability":70,"type":"madeup"}}]}"""), 0)
        assertNull(result.points.single().amountMm); assertNull(result.points.single().probability); assertEquals("unknown", result.points.single().type)
    }
    @Test fun invalidTimestampDiscardedAndDuplicatesNotDoubleCounted() {
        val result = QWeatherPrecipitationService.decodeMinutely(json("""{"code":"200","minutely":[$point,$point,{"fxTime":"invalid","precip":"5"}]}"""),0)
        assertEquals(1, result.points.size)
    }
    @Test fun emptyBusinessErrorAndMissingArraysDoNotMeanDryWeather() {
        for (text in listOf("""{"code":"403"}""", """{"code":"200","minutely":[]}""", """{"code":"200"}""")) {
            try { QWeatherPrecipitationService.decodeMinutely(json(text), 0); fail() } catch (_: EnvironmentException) { }
        }
        try { QWeatherPrecipitationService.decodeHourly(json("{}"), 0); fail() } catch (_: EnvironmentException) { }
    }
    @Test fun deniedMinuteRequestDoesNotRetryOrExposeRawDetails() = runBlocking {
        MockWebServer().use { server ->
            server.enqueue(MockResponse().setResponseCode(403).setBody("""{"error":{"type":"https://dev.qweather.com/docs/resource/error-code/#security-restriction","detail":"private-fixture"}}"""))
            try { QWeatherPrecipitationService(QWeatherClient(server.url("/").toString(), OkHttpClient())).minutely(EnvironmentArea("test",30.0,120.0)); fail() }
            catch (e: EnvironmentException) { assertTrue(e.userMessage.contains("403")); assertFalse(e.userMessage.contains("private-fixture")) }
            assertEquals(1, server.requestCount)
        }
    }
}

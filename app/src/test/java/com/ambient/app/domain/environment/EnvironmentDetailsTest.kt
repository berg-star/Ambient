package com.ambient.app.domain.environment

import com.ambient.app.data.environment.DemoEnvironmentDataService
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.data.environment.qweather.QWeatherDecoder
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class EnvironmentDetailsTest {
    private val at = Instant.parse("2026-10-18T04:00:00Z")
    private val area = EnvironmentArea("测试区域",30.0,120.0)
    private fun decode(extra: String = "") = QWeatherDecoder.decode(JsonParser.parseString(
        """{"metadata":{},"temperature":{"value":20,"unit":"°C"}$extra}""").asJsonObject,null,area,at)
    private fun metric(id: String, extra: String) = decode(extra).metrics.first { it.id == id }
    @Test fun decodesDocumentedUnitsAndCloudFractionWithProviderProvenance() {
        val snapshot = decode(""", "feelsLike":{"value":-3.2,"unit":"°C"},"dewPoint":{"value":-8.1,"unit":"°C"},"visibility":{"value":29020,"unit":"m"},"cloudCover":0.05,"windGust":{"value":7.07,"unit":"m/s"}""")
        val details=EnvironmentDetails.metrics(snapshot).associateBy { it.id }
        assertEquals("-3.2",details.getValue("feelsLike").value); assertEquals("-8.1",details.getValue("dewPoint").value)
        assertEquals("29.02",details.getValue("visibility").value); assertEquals("km",details.getValue("visibility").unit)
        assertEquals("5",details.getValue("cloudCover").value); assertEquals("7.1",details.getValue("windGust").value)
        assertTrue(details.values.all { it.source==DataSource.REGIONAL_API })
        assertEquals(at,snapshot.updatedAt); assertNull(snapshot.observedAt)
    }
    @Test fun missingValuesAreNotEstimatedFromTemperatureHumidityOrWind() {
        val snapshot=decode(""", "humidity":0.8,"wind":{"speed":{"value":3,"unit":"m/s"}}""")
        assertTrue(EnvironmentDetails.metrics(snapshot).all { it.value=="—" })
    }
    @Test fun zeroIsValidAndShortVisibilityUsesMeters() {
        val snapshot=decode(""", "feelsLike":{"value":0,"unit":"°C"},"dewPoint":{"value":0,"unit":"°C"},"visibility":{"value":0,"unit":"m"},"cloudCover":0,"windGust":{"value":0,"unit":"m/s"}""")
        assertTrue(EnvironmentDetails.metrics(snapshot).all { it.value!="—" })
        assertEquals("0.0",EnvironmentDetails.metrics(snapshot).first { it.id=="visibility" }.value)
        assertEquals("999.0",metric("visibility",""", "visibility":{"value":999,"unit":"m"}""").value)
        assertEquals("m",metric("visibility",""", "visibility":{"value":999,"unit":"m"}""").unit)
    }
    @Test fun unexpectedUnitsAreNeverRelabelled() {
        for ((id,unit) in listOf("feelsLike" to "°F","dewPoint" to "K","visibility" to "km","windGust" to "km/h"))
            assertEquals("—",metric(id,""", "$id":{"value":20,"unit":"$unit"}""").value)
    }
    @Test fun negativeOrNonFiniteDistancesAndGustsStayMissing() {
        for (id in listOf("visibility","windGust")) for (value in listOf("-1","NaN","Infinity"))
            assertEquals("—",metric(id,""", "$id":{"value":"$value","unit":"${if(id=="visibility") "m" else "m/s"}"}""").value)
        for (id in listOf("feelsLike","dewPoint"))
            assertEquals("—",metric(id,""", "$id":{"value":"NaN","unit":"°C"}""").value)
    }
    @Test fun cloudsAcceptOnlyFractionRangeWithoutClamping() {
        for(value in listOf("-0.1","1.1","50","\"NaN\"","null"))
            assertEquals("—",metric("cloudCover",""", "cloudCover":$value""").value)
        assertEquals("100",metric("cloudCover",""", "cloudCover":1""").value)
    }
    @Test fun detailsNeverEnterOverviewButRemainInSnapshotAndCodec() {
        val snapshot=decode(""", "feelsLike":{"value":22,"unit":"°C"}""")
        assertTrue(EnvironmentDetails.overviewMetrics(snapshot.metrics).none { it.id in EnvironmentDetails.ids })
        assertTrue(EnvironmentDetails.overviewMetrics(snapshot.metrics).any { it.id=="temperature" })
        assertEquals(snapshot,SnapshotCodec.decode(SnapshotCodec.encode(snapshot)))
    }
    @Test fun legacySnapshotMissingFieldsGetsPlaceholdersWithoutMutation() {
        val old=decode().let { it.copy(metrics=EnvironmentDetails.overviewMetrics(it.metrics)) }
        val metrics=EnvironmentDetails.metrics(old)
        assertEquals(5,metrics.size); assertTrue(metrics.all { it.value=="—" })
        assertTrue(old.metrics.none { it.id in EnvironmentDetails.ids }); assertEquals(at,old.updatedAt)
    }
    @Test fun demoAndUnconfiguredDetailsNeverInventMeasurements() = runBlocking {
        assertTrue(EnvironmentDetails.metrics(DemoEnvironmentDataService().fetch()).all { it.source==DataSource.DEMO && it.value=="—" })
        assertTrue(EnvironmentDetails.metrics(null).all { it.value=="—" })
    }
}

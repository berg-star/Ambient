package com.ambient.app.data.environment.qweather

import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import kotlinx.coroutines.runBlocking
import net.i2p.crypto.eddsa.EdDSAPrivateKey
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.Dispatcher
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.RecordedRequest
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.time.Clock
import java.time.Instant
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Base64

class WeatherRepositoryTest {
    private class MemoryStore : WeatherStore {
        val files = mutableMapOf<String, String>()
        var failSnapshotWrites = false
        override fun read(name: String) = files[name]
        override fun write(name: String, text: String) {
            if (name == "snapshot" && failSnapshotWrites) throw IOException("Synthetic cache write failure")
            files[name] = text
        }
        override fun delete(name: String) { files.remove(name) }
    }
    private class TestClock(var now: Instant = Instant.parse("2026-10-16T08:00:00Z")) : Clock() {
        override fun getZone(): ZoneId = ZoneOffset.UTC
        override fun withZone(zone: ZoneId): Clock = this
        override fun instant(): Instant = now
    }
    private val config = """{"developerId":"Q12345ABCD","projectId":"PROJECT01","credentialId":"CRED00001","apiHost":"test.qweatherapi.com"}"""
    private fun testPem(): String {
        // Publicly reproducible test seed, unrelated to production/user keys.
        val key = EdDSAPrivateKey(EdDSAPrivateKeySpec(ByteArray(32) { (it + 1).toByte() }, EdDSANamedCurveTable.ED_25519_CURVE_SPEC))
        return "-----BEGIN PRIVATE " + "KEY-----\n" + Base64.getEncoder().encodeToString(key.encoded) + "\n-----END PRIVATE " + "KEY-----"
    }
    private val city = EnvironmentArea("测试城", 39.92, 116.41)
    private fun setup(server: MockWebServer, store: MemoryStore, clock: Clock) = AndroidEnvironmentRepository(
        { store }, clock, { QWeatherClient(server.url("/").toString(), OkHttpClient()) })
    private fun success(server: MockWebServer, temperature: Int = 20) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(
                if (request.path!!.startsWith("/weather")) """{"metadata":{},"temperature":{"value":$temperature,"unit":"°C"}}"""
                else """{"metadata":{},"indexes":[],"pollutants":[]}""")
        }
    }

    private fun skySuccess(server: MockWebServer) {
        server.dispatcher = object : Dispatcher() {
            override fun dispatch(request: RecordedRequest): MockResponse = MockResponse().setBody(when {
                request.path!!.startsWith("/v7/astronomy/sun?") -> """{"code":"200","sunrise":"2026-10-16T06:00+08:00","sunset":"2026-10-16T18:00+08:00"}"""
                request.path!!.startsWith("/v7/astronomy/moon?") -> """{"code":"200","moonPhase":[]}"""
                else -> """{"code":"200","solarElevationAngle":"40","solarAzimuthAngle":"180"}"""
            })
        }
    }
    @Test fun skyCacheSurvivesRepositoryRecreationAndDoesNotRequestAgain() = runBlocking {
        MockWebServer().use { server ->
            skySuccess(server)
            val store = MemoryStore(); val clock = TestClock()
            val first = setup(server, store, clock)
            first.importCredentials(config, testPem()); first.selectArea(city.copy(timeZone = "Asia/Shanghai"))
            val result = first.loadSky(false)
            assertNotNull(result.snapshot); assertTrue(store.files.containsKey("sky")); assertEquals(3, server.requestCount)
            val next = setup(server, store, clock).loadSky(false)
            assertTrue(next.isCached); assertEquals(result.snapshot, next.snapshot); assertEquals(3, server.requestCount)
        }
    }
    @Test fun disabledOrUnconfiguredSkyNeverRequestsOrReturnsOldCache() = runBlocking {
        MockWebServer().use { server ->
            skySuccess(server)
            val store = MemoryStore(); val repository = setup(server, store, TestClock())
            assertNull(repository.loadSky(false).snapshot); assertEquals(0, server.requestCount)
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            assertNotNull(repository.loadSky(false).snapshot)
            repository.setEnabled(false)
            assertNull(repository.loadSky(false).snapshot); assertEquals(3, server.requestCount)
        }
    }
    @Test fun changingAreaAndCredentialsClearsSkyButNotHistory() = runBlocking {
        MockWebServer().use { server ->
            skySuccess(server)
            val store = MemoryStore(); val repository = setup(server, store, TestClock())
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            repository.loadSky(false); assertTrue(store.files.containsKey("sky"))
            repository.selectArea(city.copy(latitude = 31.0))
            assertFalse(store.files.containsKey("sky"))
            repository.loadSky(false)
            repository.importCredentials(config, testPem()); assertFalse(store.files.containsKey("sky"))
            repository.loadSky(false)
            repository.clearCredentials(); assertFalse(store.files.containsKey("sky"))
            assertNull(repository.loadSky(false).snapshot)
        }
    }

    @Test fun selectedAreaAndCacheSurviveNewRepositoryAndOfflineRefresh() = runBlocking {
        MockWebServer().use { server ->
            success(server)
            val store = MemoryStore(); val clock = TestClock()
            val first = setup(server, store, clock)
            first.importCredentials(config, testPem()); first.selectArea(city)
            val snapshot = first.refresh().snapshot!!
            assertNull(first.refresh().snapshot?.observedAt)
            val next = setup(server, store, clock)
            assertEquals(city, next.preferences().area)
            assertEquals(snapshot, next.cachedSnapshot())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
            clock.now = clock.now.plusSeconds(40)
            val failed = next.refresh()
            assertEquals(snapshot, failed.snapshot)
            assertTrue(failed.error!!.contains("503"))
            assertEquals(snapshot.updatedAt, failed.snapshot!!.updatedAt)
            assertTrue(failed.isCached)
        }
    }

    @Test fun rapidRefreshDoesNotSendMoreRequestsAndKeepsCache() = runBlocking {
        MockWebServer().use { server ->
            success(server)
            val repository = setup(server, MemoryStore(), TestClock())
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            val first = repository.refresh()
            assertFalse(first.isCached)
            assertEquals(2, server.requestCount)
            val second = repository.refresh()
            assertEquals(2, server.requestCount)
            assertEquals(first.snapshot, second.snapshot)
            assertTrue(second.error!!.contains("30 秒"))
            assertTrue(second.isCached)
        }
    }

    @Test fun selectingDifferentCoordinatesWithSameLabelCannotReuseOldSnapshot() = runBlocking {
        MockWebServer().use { server ->
            success(server)
            val repository = setup(server, MemoryStore(), TestClock())
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            assertNotNull(repository.refresh().snapshot)
            repository.selectArea(city.copy(latitude = 30.0, longitude = 110.0))
            assertNull(repository.cachedSnapshot())
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(404)
            }
            val failed = repository.refresh()
            assertNull(failed.snapshot)
            assertFalse(failed.isCached)
        }
    }

    @Test fun failedCacheWriteReturnsFreshDataAndLaterOfflineRefreshReturnsTheActualOldCache() = runBlocking {
        MockWebServer().use { server ->
            success(server)
            val store = MemoryStore(); val clock = TestClock()
            val repository = setup(server, store, clock)
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            val old = repository.refresh().snapshot!!

            clock.now = clock.now.plusSeconds(40)
            success(server, temperature = 30)
            store.failSnapshotWrites = true
            val fresh = repository.refresh()
            assertNotNull(fresh.error)
            assertTrue(fresh.error!!.contains("缓存写入失败"))
            assertFalse(fresh.isCached)
            assertEquals(clock.now, fresh.snapshot!!.updatedAt)
            assertEquals("30.0", fresh.snapshot!!.metrics.first { it.id == "temperature" }.value)
            assertEquals(old, repository.cachedSnapshot())

            clock.now = clock.now.plusSeconds(40)
            server.dispatcher = object : Dispatcher() {
                override fun dispatch(request: RecordedRequest) = MockResponse().setResponseCode(503)
            }
            val offline = repository.refresh()
            assertNotNull(offline.error)
            assertTrue(offline.isCached)
            assertEquals(old, offline.snapshot)
            assertEquals("20.0", offline.snapshot!!.metrics.first { it.id == "temperature" }.value)
        }
    }

    @Test fun invalidImportPreservesWorkingConfigurationAndClearingRemovesEverything() = runBlocking {
        MockWebServer().use { server ->
            success(server)
            val store = MemoryStore(); val repository = setup(server, store, TestClock())
            repository.importCredentials(config, testPem()); repository.selectArea(city)
            val snapshot = repository.refresh().snapshot
            try { repository.importCredentials(config, "invalid"); fail("Invalid key must fail") }
            catch (_: EnvironmentException) { /* Original configuration remains usable. */ }
            assertEquals(city, repository.preferences().area)
            assertEquals(snapshot, repository.cachedSnapshot())
            repository.clearCredentials()
            assertFalse(repository.preferences().configured)
            assertFalse(repository.preferences().enabled)
            assertNull(repository.preferences().area)
            assertNull(repository.cachedSnapshot())
            assertFalse(store.files.containsKey("credentials"))
            assertFalse(store.files.containsKey("snapshot"))
        }
    }

    @Test fun demoIsExplicitAndNeverUsesNetwork() = runBlocking {
        MockWebServer().use { server ->
            val repository = setup(server, MemoryStore(), TestClock())
            assertTrue(repository.refresh().snapshot!!.isDemo)
            assertEquals(0, server.requestCount)
            repository.importCredentials(config, testPem())
            assertNull(repository.refresh().snapshot) // No area selected: never assumes Beijing.
            assertEquals(0, server.requestCount)
        }
    }
}

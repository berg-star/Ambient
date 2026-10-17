package com.ambient.app.data.environment.qweather

import android.content.Context
import com.ambient.app.data.environment.DemoEnvironmentDataService
import com.ambient.app.data.environment.SnapshotCodec
import com.ambient.app.domain.environment.EnvironmentArea
import com.ambient.app.domain.environment.EnvironmentException
import com.ambient.app.domain.environment.EnvironmentPreferences
import com.ambient.app.domain.environment.EnvironmentResult
import com.ambient.app.domain.environment.EnvironmentSnapshot
import com.ambient.app.domain.environment.ManagedEnvironmentRepository
import com.google.gson.Gson
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.time.Duration
import java.time.Instant
import java.time.Clock
import com.ambient.app.domain.sky.SkyRepository
import com.ambient.app.domain.sky.SkyResult
import com.ambient.app.domain.sky.SkySnapshot
import com.ambient.app.data.sky.CachedSkyLoader

/** One selected-area cache, encrypted in noBackupFilesDir; never relabels another city's data. */
class AndroidEnvironmentRepository internal constructor(
    storeFactory: () -> WeatherStore,
    private val clock: Clock = Clock.systemUTC(),
    private val clientFactory: (QWeatherCredentials) -> QWeatherClient = { QWeatherClient(it) },
) : ManagedEnvironmentRepository, SkyRepository {
    constructor(context: Context) : this({ SecureWeatherStore(context.applicationContext) })
    private val store by lazy(storeFactory)
    private val lock = Mutex()
    private val gson = Gson()
    private var lastAttempt: Instant? = null
    private var credentialCache: QWeatherCredentials? = null
    private var clientCache: QWeatherClient? = null
    private var skyLoader: CachedSkyLoader? = null

    private fun profile(): EnvironmentPreferences = store.read("preferences")?.let {
        gson.fromJson(it, EnvironmentPreferences::class.java)
    } ?: EnvironmentPreferences()
    private fun save(p: EnvironmentPreferences) = store.write("preferences", gson.toJson(p))
    private fun client(): QWeatherClient {
        clientCache?.let { return it }
        val credentials = credentialCache ?: store.read("credentials")?.let { QWeatherCredentials.parse(it) }
            ?: throw EnvironmentException("请先在设置中导入和风配置与私钥。")
        credentialCache = credentials
        return clientFactory(credentials).also { clientCache = it }
    }
    private fun cached(p: EnvironmentPreferences): EnvironmentSnapshot? {
        if (!p.enabled || p.area == null) return null
        return runCatching {
            store.read("snapshot")?.let(SnapshotCodec::decode)?.takeIf { it.areaLabel == p.area.label && !it.isDemo }
        }.getOrNull()
    }

    override suspend fun preferences(): EnvironmentPreferences = withContext(Dispatchers.IO) { lock.withLock { profile() } }
    override suspend fun cachedSnapshot(): EnvironmentSnapshot? = withContext(Dispatchers.IO) { lock.withLock { cached(profile()) } }

    override suspend fun importCredentials(configJson: String, privateKeyPem: String) = withContext(Dispatchers.IO) {
        lock.withLock {
            val credentials = try { QWeatherCredentials.parse(configJson, privateKeyPem) }
            catch (_: Exception) { throw EnvironmentException("配置或私钥格式不正确。请检查四项配置，并选择 Ed25519 私钥 PEM 文件。") }
            val previous = profile()
            store.write("credentials", credentials.encode())
            credentialCache = credentials
            clientCache = null
            store.delete("snapshot")
            store.delete("sky"); skyLoader = null
            lastAttempt = null
            save(previous.copy(configured = true, enabled = true))
        }
    }
    override suspend fun clearCredentials() = withContext(Dispatchers.IO) {
        lock.withLock {
            store.delete("credentials"); store.delete("snapshot")
            store.delete("sky"); skyLoader = null
            credentialCache = null; clientCache = null; lastAttempt = null
            save(EnvironmentPreferences())
        }
    }
    override suspend fun setEnabled(enabled: Boolean) = withContext(Dispatchers.IO) {
        lock.withLock {
            val p = profile()
            if (enabled && !p.configured) throw EnvironmentException("请先导入和风配置与私钥。")
            save(p.copy(enabled = enabled))
        }
    }
    override suspend fun searchAreas(query: String): List<EnvironmentArea> = withContext(Dispatchers.IO) {
        lock.withLock {
            if (query.isBlank() || query.length > 80) throw EnvironmentException("请输入 1–80 个字符的城市名称。")
            client().cities(query.trim())
        }
    }
    override suspend fun selectArea(area: EnvironmentArea) = withContext(Dispatchers.IO) {
        lock.withLock {
            require(area.latitude.isFinite() && area.latitude in -90.0..90.0 &&
                area.longitude.isFinite() && area.longitude in -180.0..180.0 && area.label.isNotBlank())
            // Clear before changing the area, including cases with identical city labels.
            store.delete("snapshot")
            store.delete("sky"); skyLoader = null
            save(profile().copy(area = area, enabled = true))
            lastAttempt = null
        }
    }
    override suspend fun refresh(): EnvironmentResult = withContext(Dispatchers.IO) {
        lock.withLock {
            var old: EnvironmentSnapshot? = null
            try {
                val p = profile()
                if (!p.enabled) return@withLock EnvironmentResult(DemoEnvironmentDataService().fetch())
                if (!p.configured) throw EnvironmentException("请先导入和风配置与私钥。")
                val area = p.area ?: throw EnvironmentException("请选择城市，或点击“使用当前位置”。")
                old = cached(p)
                val now = clock.instant()
                val since = lastAttempt?.let { Duration.between(it, now).seconds }
                if (since != null && since in 0..29) throw EnvironmentException("请间隔 30 秒再刷新，避免重复请求。")
                lastAttempt = now
                val snapshot = QWeatherDataService(client(), area, clock).fetch()
                try { store.write("snapshot", SnapshotCodec.encode(snapshot)) }
                catch (_: Exception) {
                    return@withLock EnvironmentResult(snapshot, "本次数据已获取，但缓存写入失败；关闭后可能无法离线查看。", isCached = false)
                }
                EnvironmentResult(snapshot)
            } catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { EnvironmentResult(old, e.userMessage, isCached = old != null) }
            catch (_: Exception) { EnvironmentResult(old, "本地配置或缓存读取失败，请在设置中重新导入凭据。", isCached = old != null) }
        }
    }

    /** Shares the configuration lock: changing area/credentials cannot race a stale cache write. */
    override suspend fun loadSky(force: Boolean): SkyResult = withContext(Dispatchers.IO) {
        lock.withLock {
            try {
                val p = profile()
                if (!p.configured || !p.enabled || p.area == null)
                    return@withLock SkyResult(null, listOf("请先启用区域 API 并选择城市或使用当前位置。"))
                val old = runCatching { store.read("sky")?.let { gson.fromJson(it, SkySnapshot::class.java) } }.getOrNull()
                val loader = skyLoader ?: CachedSkyLoader(QWeatherAstronomyService(client(), clock), clock)
                    .also { skyLoader = it }
                val result = loader.load(p.area, old, force)
                if (result.snapshot != null) {
                    try { store.write("sky", gson.toJson(result.snapshot)) }
                    catch (_: Exception) { return@withLock result.copy(messages = result.messages + "天文缓存写入失败，关闭后可能无法离线查看。") }
                }
                result
            } catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { SkyResult(null, listOf(e.userMessage)) }
            catch (_: Exception) { SkyResult(null, listOf("天文配置读取失败，请检查设置后重试。")) }
        }
    }
}

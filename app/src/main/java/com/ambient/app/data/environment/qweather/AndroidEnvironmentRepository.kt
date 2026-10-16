package com.ambient.app.data.environment.qweather

import android.content.Context
import com.ambient.app.data.environment.DemoEnvironmentDataService
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

/** One selected-area cache, encrypted in noBackupFilesDir; never relabels another city's data. */
class AndroidEnvironmentRepository internal constructor(
    storeFactory: () -> WeatherStore,
    private val clock: Clock = Clock.systemUTC(),
    private val clientFactory: (QWeatherCredentials) -> QWeatherClient = { QWeatherClient(it) },
) : ManagedEnvironmentRepository {
    constructor(context: Context) : this({ SecureWeatherStore(context.applicationContext) })
    private val store by lazy(storeFactory)
    private val lock = Mutex()
    private val gson = Gson()
    private var lastAttempt: Instant? = null
    private var credentialCache: QWeatherCredentials? = null
    private var clientCache: QWeatherClient? = null

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
            lastAttempt = null
            save(previous.copy(configured = true, enabled = true))
        }
    }
    override suspend fun clearCredentials() = withContext(Dispatchers.IO) {
        lock.withLock {
            store.delete("credentials"); store.delete("snapshot")
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
                    return@withLock EnvironmentResult(snapshot, "本次数据已获取，但缓存写入失败；关闭后可能无法离线查看。")
                }
                EnvironmentResult(snapshot)
            } catch (e: CancellationException) { throw e }
            catch (e: EnvironmentException) { EnvironmentResult(old, e.userMessage) }
            catch (_: Exception) { EnvironmentResult(old, "本地配置或缓存读取失败，请在设置中重新导入凭据。") }
        }
    }
}

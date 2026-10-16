package com.ambient.app.data.environment

import com.ambient.app.domain.environment.EnvironmentDataService
import com.ambient.app.domain.environment.EnvironmentRepository
import com.ambient.app.domain.environment.EnvironmentResult
import com.ambient.app.domain.environment.EnvironmentSnapshot
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Memory cache for this stage; persistent region caching is part of network integration. */
class CachedEnvironmentRepository(
    private val service: EnvironmentDataService,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) : EnvironmentRepository {
    private var cached: EnvironmentSnapshot? = null

    override suspend fun refresh(): EnvironmentResult = withContext(dispatcher) {
        try {
            service.fetch().also { cached = it }.let { EnvironmentResult(it) }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            EnvironmentResult(cached, "数据加载失败，已保留最近一次内容；请稍后重试。")
        }
    }
}

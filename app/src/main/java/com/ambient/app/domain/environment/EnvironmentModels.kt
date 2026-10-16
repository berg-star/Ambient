package com.ambient.app.domain.environment

import java.time.Instant

enum class DataSource { DEMO, DEVICE, REGIONAL_API, ESTIMATED }

data class EnvironmentMetric(
    val id: String,
    val label: String,
    val value: String,
    val unit: String,
    val source: DataSource,
    val description: String,
)

data class EnvironmentSnapshot(
    val areaLabel: String,
    val updatedAt: Instant,
    val metrics: List<EnvironmentMetric>,
    val observedAt: Instant? = null,
    val attributions: List<String> = emptyList(),
    val notices: List<String> = emptyList(),
    val isDemo: Boolean = metrics.any { it.source == DataSource.DEMO },
)

/** Provider boundary. No URL, authentication, or provider DTO may leak into the UI. */
fun interface EnvironmentDataService {
    suspend fun fetch(): EnvironmentSnapshot
}

/** A network adapter can return cached data alongside a recoverable error. */
data class EnvironmentResult(
    val snapshot: EnvironmentSnapshot?,
    val error: String? = null,
)

interface EnvironmentRepository {
    suspend fun refresh(): EnvironmentResult
}

data class EnvironmentArea(
    val label: String,
    val latitude: Double,
    val longitude: Double,
    val fromDevice: Boolean = false,
    val isMock: Boolean = false,
)

data class EnvironmentPreferences(
    val configured: Boolean = false,
    val enabled: Boolean = false,
    val area: EnvironmentArea? = null,
)

/** Optional configuration boundary; the overview does not depend on any provider's DTOs. */
interface ManagedEnvironmentRepository : EnvironmentRepository {
    suspend fun preferences(): EnvironmentPreferences
    suspend fun cachedSnapshot(): EnvironmentSnapshot?
    suspend fun importCredentials(configJson: String, privateKeyPem: String)
    suspend fun clearCredentials()
    suspend fun setEnabled(enabled: Boolean)
    suspend fun searchAreas(query: String): List<EnvironmentArea>
    suspend fun selectArea(area: EnvironmentArea)
}

class EnvironmentException(val userMessage: String) : Exception(userMessage)

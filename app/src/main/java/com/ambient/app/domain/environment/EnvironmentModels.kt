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
)

/** Provider boundary. No URL, authentication, or provider DTO may leak into the UI. */
fun interface EnvironmentDataService {
    suspend fun fetch(): EnvironmentSnapshot
}

/** A future network adapter can return cached data alongside a recoverable error. */
data class EnvironmentResult(
    val snapshot: EnvironmentSnapshot?,
    val error: String? = null,
)

interface EnvironmentRepository {
    suspend fun refresh(): EnvironmentResult
}

package com.ambient.app.domain.environment

enum class ElevationReference { TERRAIN_ORTHOMETRIC, GNSS_ELLIPSOID, BAROMETRIC_ESTIMATE }

data class ElevationReading(
    val meters: Double,
    val reference: ElevationReference,
    val source: DataSource,
    val provider: String,
    val uncertaintyMeters: Double?,
)

/** GNSS ellipsoid height is not terrain elevation. No implementation in phase 1. */
fun interface ElevationService {
    suspend fun resolve(latitude: Double, longitude: Double): ElevationReading?
}

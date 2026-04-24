package org.freewheel.core.ride

/**
 * In-memory representation of a complete ride — metadata + all samples.
 *
 * Round-trips to/from a single GPX 1.1 file with FreeWheel extensions
 * (see `docs/ghost-routes-plan.md` for the format spec).
 */
data class RideBundle(
    val manifest: RideManifest,
    val samples: List<RideSample>,
)

/**
 * Everything about the ride that isn't a per-sample measurement.
 *
 * [rideId] is the primary dedup key on import. A bundle with the same
 * rideId must replace, not duplicate, an existing saved ride.
 */
data class RideManifest(
    val rideId: String,
    val name: String? = null,
    val startedAtUtc: Long,
    val wheelType: String? = null,
    val wheelName: String? = null,
    val wheelAddress: String? = null,
    val distanceMeters: Long? = null,
    val durationMs: Long? = null,
    val appVersion: String? = null,
    val schemaVersion: Int = SCHEMA_VERSION_V1,
) {
    companion object {
        const val SCHEMA_VERSION_V1: Int = 1
    }
}

/**
 * One GPS point plus the wheel telemetry captured at that instant.
 *
 * All telemetry fields are nullable so that imports from Strava / Garmin
 * (plain GPX, no fw extensions) yield a bundle with non-null [speedKmh]
 * only when the source provides it.
 */
data class RideSample(
    val timestampMs: Long,
    val latitude: Double,
    val longitude: Double,
    val elevationMeters: Double? = null,
    val speedKmh: Double? = null,
    val batteryPct: Double? = null,
    val pwmPct: Double? = null,
    val powerW: Double? = null,
    val voltageV: Double? = null,
    val currentA: Double? = null,
    val motorTempC: Double? = null,
    val boardTempC: Double? = null,
)

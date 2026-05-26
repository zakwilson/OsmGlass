package com.goodanser.osmglass.phone.gps

import com.goodanser.osmglass.phone.routing.LatLng
import kotlinx.coroutines.flow.Flow

/** Stream of GPS fixes. Emits at the source's natural cadence (1 Hz typical for Android GPS). */
interface GpsSource {
    /** Cancel the underlying flow to stop. */
    fun fixes(): Flow<Fix>

    data class Fix(
        val location: LatLng,
        val accuracyMeters: Float,
        val bearingDeg: Float?,
        val speedMps: Float?,
        val timestampMs: Long,
    )
}

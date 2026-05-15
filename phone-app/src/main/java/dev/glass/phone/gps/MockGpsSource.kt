package dev.glass.phone.gps

import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.cumulativeMeters
import dev.glass.phone.routing.haversineMeters
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Replays a track polyline as a stream of {@link GpsSource.Fix}es at a controllable speed
 * (default = walking speed, 5 m/s ≈ 18 km/h, conveniently bike pace too).
 *
 * Resamples the polyline at a fixed time interval so consumers see ~1 fix/second regardless of
 * polyline density.
 */
class MockGpsSource(
    private val track: List<LatLng>,
    private val speedMps: Double = 5.0,
    private val cadenceMs: Long = 1_000,
) : GpsSource {

    override fun fixes(): Flow<GpsSource.Fix> = flow {
        if (track.size < 2) return@flow
        val cum = cumulativeMeters(track)
        val totalM = cum.last()
        var elapsedSec = 0.0
        var t0 = System.currentTimeMillis()
        while (true) {
            val distM = elapsedSec * speedMps
            if (distM > totalM) break
            val (segIdx, frac) = locate(cum, distM)
            val a = track[segIdx]
            val b = track[(segIdx + 1).coerceAtMost(track.lastIndex)]
            val lat = a.lat + (b.lat - a.lat) * frac
            val lon = a.lon + (b.lon - a.lon) * frac
            val bearing = bearingDeg(a, b)
            emit(
                GpsSource.Fix(
                    location = LatLng(lat, lon),
                    accuracyMeters = 5f,
                    bearingDeg = bearing,
                    speedMps = speedMps.toFloat(),
                    timestampMs = t0 + (elapsedSec * 1000).toLong(),
                ),
            )
            delay(cadenceMs)
            elapsedSec += cadenceMs / 1000.0
        }
    }

    private fun locate(cum: DoubleArray, distM: Double): Pair<Int, Double> {
        if (distM <= 0) return 0 to 0.0
        for (i in 1 until cum.size) {
            if (cum[i] >= distM) {
                val segLen = cum[i] - cum[i - 1]
                val frac = if (segLen <= 0) 0.0 else (distM - cum[i - 1]) / segLen
                return (i - 1) to frac.coerceIn(0.0, 1.0)
            }
        }
        return cum.lastIndex - 1 to 1.0
    }

    private fun bearingDeg(a: LatLng, b: LatLng): Float {
        val φ1 = Math.toRadians(a.lat)
        val φ2 = Math.toRadians(b.lat)
        val Δλ = Math.toRadians(b.lon - a.lon)
        val y = Math.sin(Δλ) * Math.cos(φ2)
        val x = Math.cos(φ1) * Math.sin(φ2) - Math.sin(φ1) * Math.cos(φ2) * Math.cos(Δλ)
        var θ = Math.toDegrees(Math.atan2(y, x))
        if (θ < 0) θ += 360.0
        return θ.toFloat()
    }
}

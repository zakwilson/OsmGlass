package com.goodanser.osmglass.phone.routing

import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLng(val lat: Double, val lon: Double)

private const val EARTH_RADIUS_M = 6_371_008.8

/** Great-circle distance between two points in meters (haversine). */
fun haversineMeters(a: LatLng, b: LatLng): Double {
    val φ1 = Math.toRadians(a.lat)
    val φ2 = Math.toRadians(b.lat)
    val Δφ = Math.toRadians(b.lat - a.lat)
    val Δλ = Math.toRadians(b.lon - a.lon)
    val s = sin(Δφ / 2) * sin(Δφ / 2) + cos(φ1) * cos(φ2) * sin(Δλ / 2) * sin(Δλ / 2)
    val c = 2 * atan2(sqrt(s), sqrt(1 - s))
    return EARTH_RADIUS_M * c
}

/** Cumulative distance for a polyline; returns array of length points.size with [0] == 0. */
fun cumulativeMeters(points: List<LatLng>): DoubleArray {
    val out = DoubleArray(points.size)
    for (i in 1 until points.size) {
        out[i] = out[i - 1] + haversineMeters(points[i - 1], points[i])
    }
    return out
}

/** Initial bearing from a to b in degrees clockwise from north. */
fun bearingDeg(a: LatLng, b: LatLng): Double {
    val φ1 = Math.toRadians(a.lat)
    val φ2 = Math.toRadians(b.lat)
    val Δλ = Math.toRadians(b.lon - a.lon)
    val y = sin(Δλ) * cos(φ2)
    val x = cos(φ1) * sin(φ2) - sin(φ1) * cos(φ2) * cos(Δλ)
    var θ = Math.toDegrees(atan2(y, x))
    if (θ < 0) θ += 360.0
    return θ
}

/** Index of the track point closest to {@code at}, or null if the track is empty. */
fun nearestTrackIndex(track: List<LatLng>, at: LatLng): Int? {
    if (track.isEmpty()) return null
    var bestIdx = 0
    var bestDist = haversineMeters(track[0], at)
    for (i in 1 until track.size) {
        val d = haversineMeters(track[i], at)
        if (d < bestDist) {
            bestDist = d
            bestIdx = i
        }
    }
    return bestIdx
}

/**
 * Bearing of the track approaching {@code at}, averaged over up to {@code lookbackM} meters
 * walked backwards from the nearest track point. Returns null if not enough track exists
 * before the point (e.g. the route's start).
 */
fun approachBearingDeg(track: List<LatLng>, at: LatLng, lookbackM: Double = 50.0): Double? {
    val idx = nearestTrackIndex(track, at) ?: return null
    if (idx <= 0) return null
    var remaining = lookbackM
    var i = idx
    while (i > 0 && remaining > 0) {
        remaining -= haversineMeters(track[i - 1], track[i])
        i--
    }
    if (i == idx) return null
    return bearingDeg(track[i], track[idx])
}

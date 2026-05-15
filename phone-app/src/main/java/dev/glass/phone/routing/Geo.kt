package dev.glass.phone.routing

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

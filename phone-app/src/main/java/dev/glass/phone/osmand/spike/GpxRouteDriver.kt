package dev.glass.phone.osmand.spike

import android.content.Context
import android.util.Log
import dev.glass.phone.osmand.OsmAndAidlClient
import dev.glass.phone.routing.LatLng
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory
import java.io.InputStream
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Drives a GPS simulation by injecting positions into OsmAnd via setLocation. Avoids relying on
 * OsmAnd's built-in "Simulate your position", which has been observed to fall through to real GPS
 * mid-session.
 *
 * Reads a GPX track (just trkpt lat/lon), interpolates points at `tickHz` along the polyline at
 * `speedMps`, and pushes each one. timeToNotUseOtherGPS is set generously per tick so OsmAnd
 * keeps ignoring the real provider between ticks.
 */
class GpxRouteDriver(
    private val client: OsmAndAidlClient,
    private val track: List<LatLng>,
    private val speedMps: Float = 4f,
    private val tickHz: Int = 1,
) {

    private var job: Job? = null

    val pointCount: Int get() = track.size

    fun start(scope: CoroutineScope, onTick: (LatLng) -> Unit = {}) {
        if (track.size < 2) {
            Log.w(TAG, "track has <2 points, nothing to drive")
            return
        }
        job?.cancel()
        val tickMs = 1000L / tickHz.coerceAtLeast(1)
        val stepM = speedMps / tickHz
        job = scope.launch(Dispatchers.Default) {
            var segIdx = 0
            var carry = 0.0
            // Prime: tell OsmAnd we are at the start before stepping.
            pushFix(track[0], track[0], track[1])
            while (isActive && segIdx < track.size - 1) {
                val a = track[segIdx]
                val b = track[segIdx + 1]
                val segLen = haversineM(a, b)
                val remaining = segLen - carry
                if (remaining < stepM) {
                    carry = stepM - remaining
                    segIdx++
                    continue
                }
                val newCarry = carry + stepM
                val frac = newCarry / segLen
                val here = LatLng(
                    a.lat + (b.lat - a.lat) * frac,
                    a.lon + (b.lon - a.lon) * frac,
                )
                pushFix(here, a, b)
                onTick(here)
                carry = newCarry
                delay(tickMs)
            }
            // Final fix: arrival.
            val last = track.last()
            val prev = track[track.size - 2]
            pushFix(last, prev, last)
            onTick(last)
        }
    }

    fun stop() {
        job?.cancel()
        job = null
    }

    private fun pushFix(here: LatLng, segStart: LatLng, segEnd: LatLng) {
        val bearing = bearingDeg(segStart, segEnd).toFloat()
        client.setLocation(
            lat = here.lat, lon = here.lon,
            bearing = bearing, speedMps = speedMps,
            timeToNotUseOtherGpsMs = 5_000L,
        )
    }

    companion object {
        private const val TAG = "GpxRouteDriver"

        fun loadAsset(context: Context, assetName: String): List<LatLng> =
            context.assets.open(assetName).use { parseGpx(it) }

        fun parseGpx(input: InputStream): List<LatLng> {
            val parser = XmlPullParserFactory.newInstance().newPullParser()
            parser.setInput(input, null)
            val out = mutableListOf<LatLng>()
            var ev = parser.eventType
            while (ev != XmlPullParser.END_DOCUMENT) {
                if (ev == XmlPullParser.START_TAG && parser.name == "trkpt") {
                    val lat = parser.getAttributeValue(null, "lat")?.toDoubleOrNull()
                    val lon = parser.getAttributeValue(null, "lon")?.toDoubleOrNull()
                    if (lat != null && lon != null) out.add(LatLng(lat, lon))
                }
                ev = parser.next()
            }
            return out
        }

        private const val EARTH_M = 6_371_000.0

        private fun haversineM(a: LatLng, b: LatLng): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLat = lat2 - lat1
            val dLon = Math.toRadians(b.lon - a.lon)
            val s = sin(dLat / 2).let { it * it } +
                cos(lat1) * cos(lat2) * sin(dLon / 2).let { it * it }
            return 2 * EARTH_M * atan2(sqrt(s), sqrt(1 - s))
        }

        private fun bearingDeg(a: LatLng, b: LatLng): Double {
            val lat1 = Math.toRadians(a.lat)
            val lat2 = Math.toRadians(b.lat)
            val dLon = Math.toRadians(b.lon - a.lon)
            val y = sin(dLon) * cos(lat2)
            val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
            return (Math.toDegrees(atan2(y, x)) + 360.0) % 360.0
        }
    }
}

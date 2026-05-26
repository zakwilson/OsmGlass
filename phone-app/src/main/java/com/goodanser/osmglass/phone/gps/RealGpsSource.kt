package com.goodanser.osmglass.phone.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.Bundle
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import com.goodanser.osmglass.phone.routing.LatLng
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real platform GPS via {@link LocationManager}. Requires {@code ACCESS_FINE_LOCATION} permission.
 *
 * Subscribes to every useful provider simultaneously (GPS, NETWORK, PASSIVE, and FUSED on
 * API 31+) so that the fix stream keeps flowing when one provider goes silent — e.g. in urban
 * canyons, tunnels, or indoors where a pure GPS_PROVIDER stream stalls for minutes. Incoming
 * fixes are merged with the standard "is-better-location" heuristic so that lower-quality
 * sources only emit when they are meaningfully fresher than the last GPS fix.
 */
class RealGpsSource(
    private val context: Context,
    private val minTimeMs: Long = 1_000,
    private val minDistanceM: Float = 0f,
) : GpsSource {

    @SuppressLint("MissingPermission")
    override fun fixes(): Flow<GpsSource.Fix> = callbackFlow {
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION)
            != PackageManager.PERMISSION_GRANTED) {
            close(SecurityException("ACCESS_FINE_LOCATION not granted"))
            return@callbackFlow
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
        val providers = candidateProviders()
        // All callbacks share the main looper so this mutable state is single-threaded.
        var best: Location? = null
        val listeners = providers.mapNotNull { provider ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (isBetterLocation(location, best)) {
                        best = location
                        trySend(location.toFix())
                    }
                }
                override fun onProviderEnabled(provider: String) {}
                override fun onProviderDisabled(provider: String) {}
                override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) {}
            }
            // Passive provider must not throttle — we want to opportunistically pick up any
            // fix the OS pushes to other apps. The minTime hint also acts as a floor only;
            // providers may emit slower in poor conditions.
            val rateMs = if (provider == LocationManager.PASSIVE_PROVIDER) 0L else minTimeMs
            try {
                lm.requestLocationUpdates(provider, rateMs, minDistanceM, listener, Looper.getMainLooper())
                provider to listener
            } catch (t: Throwable) {
                Log.w(TAG, "requestLocationUpdates($provider) failed: ${t.message}")
                null
            }
        }
        if (listeners.isEmpty()) {
            close(IllegalStateException("no location providers available"))
            return@callbackFlow
        }
        // Seed with the freshest last-known fix so consumers don't sit at null while the
        // providers warm up. This often shortens the "no position" window after start.
        seedLastKnown(lm, providers)?.let { seed ->
            best = seed
            trySend(seed.toFix())
        }
        awaitClose {
            for ((_, l) in listeners) {
                try { lm.removeUpdates(l) } catch (_: Throwable) {}
            }
        }
    }

    private fun candidateProviders(): List<String> {
        val list = mutableListOf<String>()
        // FUSED already merges GPS / network / sensors when present; still keep GPS for the
        // raw signal because FUSED can lag and we want the fastest possible bearing/speed.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            list += LocationManager.FUSED_PROVIDER
        }
        list += LocationManager.GPS_PROVIDER
        list += LocationManager.NETWORK_PROVIDER
        list += LocationManager.PASSIVE_PROVIDER
        return list
    }

    @SuppressLint("MissingPermission")
    private fun seedLastKnown(lm: LocationManager, providers: List<String>): Location? {
        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: Throwable) { null }
            if (l != null && isBetterLocation(l, best)) best = l
        }
        return best
    }

    private fun Location.toFix(): GpsSource.Fix = GpsSource.Fix(
        location = LatLng(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else Float.NaN,
        bearingDeg = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null,
        timestampMs = time,
    )

    companion object {
        private const val TAG = "RealGpsSource"
        private const val SIGNIFICANT_TIME_GAP_MS = 30_000L
        private const val SIGNIFICANT_ACCURACY_GAP_M = 200f

        /**
         * Adapted from the canonical Android "is-better-location" heuristic. Accepts the candidate
         * fix when it's clearly fresher, clearly more accurate, or comparable to the current best.
         */
        internal fun isBetterLocation(candidate: Location, current: Location?): Boolean {
            if (current == null) return true
            val timeDelta = candidate.time - current.time
            if (timeDelta > SIGNIFICANT_TIME_GAP_MS) return true
            if (timeDelta < -SIGNIFICANT_TIME_GAP_MS) return false
            val isNewer = timeDelta > 0

            val candAcc = if (candidate.hasAccuracy()) candidate.accuracy else Float.MAX_VALUE
            val currAcc = if (current.hasAccuracy()) current.accuracy else Float.MAX_VALUE
            val accuracyDelta = candAcc - currAcc
            val isMoreAccurate = accuracyDelta < 0f
            val isLessAccurate = accuracyDelta > 0f
            val isSignificantlyLessAccurate = accuracyDelta > SIGNIFICANT_ACCURACY_GAP_M
            val sameProvider = candidate.provider == current.provider

            return when {
                isMoreAccurate -> true
                isNewer && !isLessAccurate -> true
                isNewer && !isSignificantlyLessAccurate && sameProvider -> true
                else -> false
            }
        }
    }
}

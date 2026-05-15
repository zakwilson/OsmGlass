package dev.glass.phone.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import android.util.Log
import androidx.core.content.ContextCompat
import dev.glass.phone.routing.LatLng
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume

/**
 * One-shot current-location getter. Returns the freshest location available within {@code timeoutMs},
 * or null if no fix is obtained / the permission is missing.
 *
 * Strategy:
 *   1. Try {@link LocationManager#getLastKnownLocation} on GPS, NETWORK, FUSED — pick the freshest
 *      fix newer than {@link MAX_AGE_MS}.
 *   2. If none is fresh enough, request a single fresh update with the configured timeout.
 */
class LocationProvider(private val context: Context) {

    suspend fun getCurrentLocation(timeoutMs: Long = 8_000): LatLng? {
        if (!hasPermission()) {
            Log.w(TAG, "ACCESS_FINE_LOCATION not granted")
            return null
        }
        val lm = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager

        // 1) Try last-known.
        val recent = freshestLastKnown(lm)
        if (recent != null) {
            Log.i(TAG, "using last-known fix from provider=${recent.provider}")
            return LatLng(recent.latitude, recent.longitude)
        }

        // 2) Active request, timeout-bounded.
        val provider = lm.getProviders(true).firstOrNull { p ->
            p == LocationManager.GPS_PROVIDER || p == LocationManager.NETWORK_PROVIDER
        } ?: lm.allProviders.firstOrNull() ?: run {
            Log.w(TAG, "no location provider available")
            return null
        }
        Log.i(TAG, "requesting fresh fix from provider=$provider")

        return withTimeoutOrNull(timeoutMs) {
            requestSingleUpdate(lm, provider)?.let { LatLng(it.latitude, it.longitude) }
        }
    }

    @SuppressLint("MissingPermission")
    private fun freshestLastKnown(lm: LocationManager): Location? {
        val now = System.currentTimeMillis()
        val providers = lm.allProviders
        var best: Location? = null
        for (p in providers) {
            val l = try { lm.getLastKnownLocation(p) } catch (_: SecurityException) { null }
            if (l != null && (now - l.time) <= MAX_AGE_MS) {
                if (best == null || l.time > best.time) best = l
            }
        }
        return best
    }

    @SuppressLint("MissingPermission")
    private suspend fun requestSingleUpdate(lm: LocationManager, provider: String): Location? =
        suspendCancellableCoroutine { cont ->
            val listener = object : LocationListener {
                override fun onLocationChanged(location: Location) {
                    if (cont.isActive) {
                        try { lm.removeUpdates(this) } catch (_: Throwable) {}
                        cont.resume(location)
                    }
                }
                override fun onProviderEnabled(p: String) {}
                override fun onProviderDisabled(p: String) {
                    if (cont.isActive) {
                        try { lm.removeUpdates(this) } catch (_: Throwable) {}
                        cont.resume(null)
                    }
                }
                override fun onStatusChanged(p: String?, s: Int, b: android.os.Bundle?) {}
            }
            try {
                lm.requestLocationUpdates(provider, 0L, 0f, listener, Looper.getMainLooper())
            } catch (t: Throwable) {
                Log.w(TAG, "requestLocationUpdates failed: ${t.message}")
                cont.resume(null)
                return@suspendCancellableCoroutine
            }
            cont.invokeOnCancellation {
                try { lm.removeUpdates(listener) } catch (_: Throwable) {}
            }
        }

    private fun hasPermission(): Boolean = ContextCompat.checkSelfPermission(
        context, Manifest.permission.ACCESS_FINE_LOCATION,
    ) == PackageManager.PERMISSION_GRANTED

    companion object {
        private const val TAG = "LocationProvider"
        private const val MAX_AGE_MS = 30_000L
    }
}

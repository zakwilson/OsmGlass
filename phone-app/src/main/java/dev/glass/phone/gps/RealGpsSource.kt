package dev.glass.phone.gps

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.core.content.ContextCompat
import dev.glass.phone.routing.LatLng
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow

/**
 * Real platform GPS via {@link LocationManager}. Requires {@code ACCESS_FINE_LOCATION} permission.
 */
class RealGpsSource(
    private val context: Context,
    private val provider: String = LocationManager.GPS_PROVIDER,
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
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) { trySend(location.toFix()) }
            override fun onProviderEnabled(provider: String) {}
            override fun onProviderDisabled(provider: String) {}
            override fun onStatusChanged(provider: String?, status: Int, extras: android.os.Bundle?) {}
        }
        // Pass the main Looper explicitly: callers run this from a coroutine worker thread that
        // has no Looper of its own; the 4-arg overload would crash with
        // "Can't create handler inside thread … that has not called Looper.prepare()".
        lm.requestLocationUpdates(provider, minTimeMs, minDistanceM, listener, Looper.getMainLooper())
        awaitClose { lm.removeUpdates(listener) }
    }

    private fun Location.toFix(): GpsSource.Fix = GpsSource.Fix(
        location = LatLng(latitude, longitude),
        accuracyMeters = if (hasAccuracy()) accuracy else Float.NaN,
        bearingDeg = if (hasBearing()) bearing else null,
        speedMps = if (hasSpeed()) speed else null,
        timestampMs = time,
    )
}

package dev.glass.phone.osmand

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import dev.glass.phone.routing.LatLng
import dev.glass.phone.routing.Turn
import dev.glass.phone.routing.haversineMeters
import dev.glass.phone.routing.nearestTrackIndex
import net.osmand.aidlapi.gpx.CreateGpxBitmapParams
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders per-turn map snippets via OsmAnd's AIDL `getBitmapForGpx`. For each turn, slices the
 * real route polyline to a [WINDOW_M] window on each side of the turn point, writes it as GPX
 * to a cache subdir, hands OsmAnd a FileProvider URI with read permission, and returns the PNG
 * bytes.
 *
 * If OsmAnd is not installed or bind fails, [render] returns empty PNGs for every turn — callers
 * (RideService.pushRoute) will still send TURN_BUNDLEs without an image attached.
 *
 * North-up always; OsmAnd does not accept a rotation parameter for this call.
 */
class OsmAndSnippetRenderer(private val context: Context) {

    suspend fun render(turns: List<Turn>, track: List<LatLng>): List<ByteArray> {
        if (turns.isEmpty()) return emptyList()
        val client = OsmAndAidlClient(context)
        val connected = try { client.connect() } catch (e: Throwable) {
            Log.w(TAG, "OsmAnd bind failed; TURN_BUNDLEs will ship without snippets", e)
            false
        }
        if (!connected) {
            try { client.disconnect() } catch (_: Throwable) {}
            return List(turns.size) { EMPTY }
        }
        val osmAndPkg = client.osmAndPackage
        val dir = File(context.cacheDir, "route-snippets").apply { mkdirs() }
        val results = ArrayList<ByteArray>(turns.size)
        try {
            for ((idx, turn) in turns.withIndex()) {
                results += try {
                    val window = sliceAroundTurn(track, turn, WINDOW_M)
                    if (window.size < 2) {
                        Log.w(TAG, "no usable track window for turn $idx; skipping snippet")
                        EMPTY
                    } else {
                        renderOne(client, osmAndPkg, dir, idx, window)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "snippet render failed for turn $idx", e)
                    EMPTY
                }
            }
        } finally {
            try { client.disconnect() } catch (_: Throwable) {}
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        return results
    }

    private suspend fun renderOne(
        client: OsmAndAidlClient,
        osmAndPkg: String?,
        dir: File,
        idx: Int,
        window: List<LatLng>,
    ): ByteArray {
        val gpx = File(dir, "turn-$idx.gpx")
        writeTrackGpx(gpx, window)
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.spike.fileprovider",
            gpx,
        )
        if (osmAndPkg != null) {
            context.grantUriPermission(osmAndPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            val params = CreateGpxBitmapParams(uri, DENSITY, WIDTH, HEIGHT, Color.RED)
            val bmp = client.getBitmapForGpx(params) ?: return EMPTY
            return ByteArrayOutputStream().use {
                bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            if (osmAndPkg != null) {
                context.revokeUriPermission(osmAndPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { gpx.delete() }
        }
    }

    private fun writeTrackGpx(file: File, points: List<LatLng>) {
        val pts = points.joinToString("\n") { "    <trkpt lat=\"${it.lat}\" lon=\"${it.lon}\"/>" }
        file.writeText(
            """<?xml version="1.0" encoding="UTF-8"?>
            |<gpx version="1.1" creator="glass-nav" xmlns="http://www.topografix.com/GPX/1/1">
            |  <trk><trkseg>
            |$pts
            |  </trkseg></trk>
            |</gpx>
            |""".trimMargin()
        )
    }

    companion object {
        private const val TAG = "OsmAndSnippetRenderer"
        private val EMPTY = ByteArray(0)
        const val WIDTH = 640
        const val HEIGHT = 360
        const val DENSITY = 2.5f

        /** Polyline distance kept on each side of the turn point in the snippet GPX. */
        const val WINDOW_M = 150.0

        /**
         * Slice the real route polyline to a window centered on [turn], extending out to
         * [windowM] meters of polyline distance in each direction. Returns an empty list if
         * [track] is empty.
         */
        fun sliceAroundTurn(track: List<LatLng>, turn: Turn, windowM: Double): List<LatLng> {
            val turnLatLng = LatLng(turn.lat, turn.lon)
            val anchor = nearestTrackIndex(track, turnLatLng) ?: return emptyList()
            var start = anchor
            var back = 0.0
            while (start > 0 && back < windowM) {
                back += haversineMeters(track[start - 1], track[start])
                start--
            }
            var end = anchor
            var fwd = 0.0
            while (end < track.lastIndex && fwd < windowM) {
                fwd += haversineMeters(track[end], track[end + 1])
                end++
            }
            return track.subList(start, end + 1).toList()
        }
    }
}

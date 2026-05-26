package com.goodanser.osmglass.phone.osmand

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import com.goodanser.osmglass.phone.routing.LatLng
import com.goodanser.osmglass.phone.routing.Turn
import com.goodanser.osmglass.phone.routing.haversineMeters
import com.goodanser.osmglass.phone.routing.nearestTrackIndex
import net.osmand.aidlapi.gpx.CreateGpxBitmapParams
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * Renders per-turn map snippets via OsmAnd's AIDL `getBitmapForGpx`. For each turn, slices the
 * real route polyline to a [WINDOW_M] window on each side of the turn point, writes it as GPX
 * to a cache subdir, hands OsmAnd a FileProvider URI with read permission, and returns both the
 * PNG bytes and the [SnippetBounds] used to project the live position marker into the bitmap.
 *
 * If OsmAnd is not installed or bind fails, [render] returns empty PNGs for every turn — callers
 * (RideService.pushRoute) will still send TURN_BUNDLEs without an image attached. Bounds are
 * still produced from the polyline window so the position-marker pipeline keeps working even
 * with placeholder snippets.
 *
 * North-up always; OsmAnd does not accept a rotation parameter for this call.
 */
class OsmAndSnippetRenderer(private val context: Context) {

    /**
     * Output of [render] for a single turn. [pngBytes] may be empty (OsmAnd unavailable or this
     * turn's window was too short to render). [bounds] is null only when the polyline window for
     * this turn was empty, which means the marker pipeline cannot do anything with this snippet.
     */
    data class Snippet(val pngBytes: ByteArray, val bounds: SnippetBounds?) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is Snippet) return false
            return pngBytes.contentEquals(other.pngBytes) && bounds == other.bounds
        }
        override fun hashCode(): Int = pngBytes.contentHashCode() * 31 + (bounds?.hashCode() ?: 0)
    }

    suspend fun render(turns: List<Turn>, track: List<LatLng>): List<Snippet> {
        if (turns.isEmpty()) return emptyList()
        val client = OsmAndAidlClient(context)
        val connected = try { client.connect() } catch (e: Throwable) {
            Log.w(TAG, "OsmAnd bind failed; TURN_BUNDLEs will ship without snippets", e)
            false
        }
        if (!connected) {
            try { client.disconnect() } catch (_: Throwable) {}
            // Still emit per-turn bounds so the position marker can be projected; the LiveCard
            // simply has no map underneath.
            return turns.map { Snippet(EMPTY, boundsForTurn(track, it)) }
        }
        val osmAndPkg = client.osmAndPackage
        val dir = File(context.cacheDir, "route-snippets").apply { mkdirs() }
        val results = ArrayList<Snippet>(turns.size)
        try {
            for ((idx, turn) in turns.withIndex()) {
                val window = sliceAroundTurn(track, turn, WINDOW_M)
                val bounds = SnippetBounds.fromWindow(window, WIDTH, HEIGHT)
                val png = try {
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
                results += Snippet(png, bounds)
            }
        } finally {
            try { client.disconnect() } catch (_: Throwable) {}
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        return results
    }

    private fun boundsForTurn(track: List<LatLng>, turn: Turn): SnippetBounds? {
        val window = sliceAroundTurn(track, turn, WINDOW_M)
        return SnippetBounds.fromWindow(window, WIDTH, HEIGHT)
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

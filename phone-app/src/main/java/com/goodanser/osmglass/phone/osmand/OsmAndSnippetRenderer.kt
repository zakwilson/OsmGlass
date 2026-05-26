package com.goodanser.osmglass.phone.osmand

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.util.Log
import androidx.core.content.FileProvider
import com.goodanser.osmglass.phone.routing.LatLng
import com.goodanser.osmglass.phone.routing.Turn
import com.goodanser.osmglass.phone.routing.haversineMeters
import com.goodanser.osmglass.phone.routing.nearestTrackIndex
import com.goodanser.osmglass.phone.ui.DisplayPrefs.MapOrientation
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
 * To avoid black corners after travel-up rotation, OsmAnd renders into a square source bitmap
 * ([SRC_SIZE] × [SRC_SIZE]) sized so a 640×360 destination rectangle fits inside it at any
 * rotation. For [MapOrientation.TRAVEL_UP] the source is rotated around its center so the route
 * entry direction points up; for both orientations we then crop the central [WIDTH]×[HEIGHT]
 * rectangle and ship that to Glass. The matching [SnippetBounds] is built with render dims =
 * SRC_SIZE so the meters-per-pixel reflects what OsmAnd actually drew, while the destination
 * dims = [WIDTH]/[HEIGHT] govern where the live position marker lands and the in-bounds check.
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

    suspend fun render(
        turns: List<Turn>,
        track: List<LatLng>,
        orientation: MapOrientation = MapOrientation.NORTH_UP,
    ): List<Snippet> {
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
            return turns.map { Snippet(EMPTY, boundsForTurn(track, it, orientation)) }
        }
        val osmAndPkg = client.osmAndPackage
        val dir = File(context.cacheDir, "route-snippets").apply { mkdirs() }
        val results = ArrayList<Snippet>(turns.size)
        try {
            for ((idx, turn) in turns.withIndex()) {
                val window = sliceAroundTurn(track, turn, WINDOW_M)
                val baseBounds = SnippetBounds.fromWindow(
                    window, WIDTH, HEIGHT, SRC_SIZE, SRC_SIZE,
                )
                val outBounds = baseBounds?.applyOrientation(orientation)
                val png = try {
                    if (window.size < 2) {
                        Log.w(TAG, "no usable track window for turn $idx; skipping snippet")
                        EMPTY
                    } else {
                        renderOne(client, osmAndPkg, dir, idx, window, baseBounds, orientation)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "snippet render failed for turn $idx", e)
                    EMPTY
                }
                results += Snippet(png, outBounds)
            }
        } finally {
            try { client.disconnect() } catch (_: Throwable) {}
            dir.listFiles()?.forEach { runCatching { it.delete() } }
        }
        return results
    }

    private fun boundsForTurn(
        track: List<LatLng>,
        turn: Turn,
        orientation: MapOrientation,
    ): SnippetBounds? {
        val window = sliceAroundTurn(track, turn, WINDOW_M)
        return SnippetBounds.fromWindow(window, WIDTH, HEIGHT, SRC_SIZE, SRC_SIZE)
            ?.applyOrientation(orientation)
    }

    private fun SnippetBounds.applyOrientation(orientation: MapOrientation): SnippetBounds =
        when (orientation) {
            MapOrientation.NORTH_UP -> this
            MapOrientation.TRAVEL_UP -> rotatedTravelUp()
        }

    private suspend fun renderOne(
        client: OsmAndAidlClient,
        osmAndPkg: String?,
        dir: File,
        idx: Int,
        window: List<LatLng>,
        baseBounds: SnippetBounds?,
        orientation: MapOrientation,
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
            val params = CreateGpxBitmapParams(uri, DENSITY, SRC_SIZE, SRC_SIZE, Color.RED)
            val srcBmp = client.getBitmapForGpx(params) ?: return EMPTY
            val rotated = if (orientation == MapOrientation.TRAVEL_UP && baseBounds != null) {
                rotateAroundCenter(srcBmp, -baseBounds.startBearingDeg)
            } else srcBmp
            val finalBmp = Bitmap.createBitmap(
                rotated, CROP_X, CROP_Y, WIDTH, HEIGHT,
            )
            return ByteArrayOutputStream().use {
                finalBmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                it.toByteArray()
            }
        } finally {
            if (osmAndPkg != null) {
                context.revokeUriPermission(osmAndPkg, uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
            runCatching { gpx.delete() }
        }
    }

    /** Rotate [src] around its center by [degrees] (Canvas convention; same sign as
     *  [android.graphics.Canvas.rotate]) onto a fresh bitmap of the same dimensions. The four
     *  corners of [src] rotate off-canvas and are discarded; the caller then crops the central
     *  [WIDTH]×[HEIGHT] rectangle, which fits inside the rotated square at any angle as long as
     *  [SRC_SIZE] ≥ √([WIDTH]² + [HEIGHT]²). */
    private fun rotateAroundCenter(src: Bitmap, degrees: Double): Bitmap {
        val w = src.width
        val h = src.height
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        canvas.rotate(degrees.toFloat(), w / 2f, h / 2f)
        canvas.drawBitmap(src, 0f, 0f, null)
        return out
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
        /** Glass screen dimensions — the final cropped bitmap delivered to the LiveCard. */
        const val WIDTH = 640
        const val HEIGHT = 360
        const val DENSITY = 2.5f

        /** Side of the square source bitmap OsmAnd renders into. Must be ≥ √(WIDTH² + HEIGHT²)
         *  (≈ 734.16) so a [WIDTH]×[HEIGHT] destination fits inside it at any rotation; 736 is
         *  the next multiple of 8 above that bound. */
        const val SRC_SIZE = 736

        /** Top-left of the centred [WIDTH]×[HEIGHT] crop inside the [SRC_SIZE]×[SRC_SIZE] source. */
        const val CROP_X = (SRC_SIZE - WIDTH) / 2
        const val CROP_Y = (SRC_SIZE - HEIGHT) / 2

        /** Polyline distance kept on each side of the turn point in the snippet GPX. Wider than
         *  the displayed area so OsmAnd zooms out enough that the rotated+cropped destination is
         *  filled with map content rather than the route running off the edges. */
        const val WINDOW_M = 300.0

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

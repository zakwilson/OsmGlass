package dev.glass.phone.render

import android.content.Context
import android.util.Log
import java.io.File

/**
 * Locates a Mapsforge `.map` data file for the device. Looks in three conventional dirs in order:
 *   1. {@code <app filesDir>/maps/}              (app-private, no permissions)
 *   2. {@code /sdcard/glass-cycling/maps/}       (user-friendly drop folder)
 *   3. {@code /sdcard/Android/data/<pkg>/files/maps/}  (where adb push naturally lands)
 *
 * Resolution: tries {@code <region>.map} first, then any other `.map` file in the same dir
 * (so pushing e.g. {@code florida.map} works without renaming it to {@code berlin.map}). Returns
 * the largest matching file when there are multiple — assumes a bigger file = more coverage.
 */
class MapDataSource(private val context: Context) {

    private val dirs: List<File> = listOf(
        File(context.filesDir, "maps"),
        File("/sdcard/glass-cycling/maps"),
        File("/sdcard/Android/data/${context.packageName}/files/maps"),
    )

    /** The resolved file, or null if no .map is present in any known location. */
    fun resolve(region: String = "berlin"): File? {
        // Pass 1: exact name match (e.g. "berlin.map") in any dir.
        for (dir in dirs) {
            val exact = File(dir, "$region.map")
            if (exact.exists() && exact.length() > 0) {
                Log.i(TAG, "using map: ${exact.absolutePath} (${exact.length() / 1024} KB)")
                return exact
            }
        }
        // Pass 2: any *.map in any dir, largest first.
        val candidates = dirs.flatMap { dir ->
            (dir.listFiles { f -> f.isFile && f.name.endsWith(".map") && f.length() > 0 } ?: emptyArray()).toList()
        }
        val pick = candidates.maxByOrNull { it.length() }
        if (pick != null) {
            Log.i(TAG, "using map: ${pick.absolutePath} (${pick.length() / 1024} KB) — region '$region.map' not found, picked largest")
        } else {
            Log.w(TAG, "no .map file found under: ${dirs.joinToString { it.absolutePath }}")
        }
        return pick
    }

    /** Ensure the app's maps directory exists. Returns the directory. */
    fun ensureMapsDir(): File {
        val dir = dirs.first()
        if (!dir.exists()) dir.mkdirs()
        return dir
    }

    companion object {
        private const val TAG = "MapDataSource"
    }
}

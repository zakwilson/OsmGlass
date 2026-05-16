package dev.glass.phone.ui

import android.content.Context
import android.content.SharedPreferences

/** Map orientation: north-up vs travel-direction-up. Persists across rides. */
object OrientationPrefs {
    private const val FILE = "orientation"
    private const val KEY_MODE = "mode"

    enum class Mode { NORTH_UP, TRAVEL_UP }

    private fun prefs(ctx: Context): SharedPreferences =
        ctx.applicationContext.getSharedPreferences(FILE, Context.MODE_PRIVATE)

    fun get(ctx: Context): Mode =
        if (prefs(ctx).getString(KEY_MODE, "north") == "travel") Mode.TRAVEL_UP else Mode.NORTH_UP

    fun set(ctx: Context, mode: Mode) {
        prefs(ctx).edit().putString(KEY_MODE, if (mode == Mode.TRAVEL_UP) "travel" else "north").apply()
    }
}

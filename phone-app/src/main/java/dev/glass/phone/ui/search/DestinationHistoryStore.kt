package dev.glass.phone.ui.search

import android.content.Context
import android.content.SharedPreferences
import dev.glass.phone.routing.LatLng
import org.json.JSONArray
import org.json.JSONObject

class DestinationHistoryStore(context: Context) {

    private val prefs: SharedPreferences =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun list(): List<Place> {
        val raw = prefs.getString(KEY, null) ?: return emptyList()
        return runCatching {
            val arr = JSONArray(raw)
            buildList(arr.length()) {
                for (i in 0 until arr.length()) {
                    val o = arr.getJSONObject(i)
                    add(Place(o.getString("name"), LatLng(o.getDouble("lat"), o.getDouble("lon"))))
                }
            }
        }.getOrElse { emptyList() }
    }

    fun add(place: Place) {
        val current = list().toMutableList()
        current.removeAll { it.displayName == place.displayName && it.location == place.location }
        current.add(0, place)
        save(current.take(MAX_ENTRIES))
    }

    fun remove(place: Place) {
        val current = list().toMutableList()
        current.removeAll { it.displayName == place.displayName && it.location == place.location }
        save(current)
    }

    fun clear() {
        prefs.edit().remove(KEY).apply()
    }

    private fun save(items: List<Place>) {
        val arr = JSONArray()
        for (p in items) {
            arr.put(
                JSONObject()
                    .put("name", p.displayName)
                    .put("lat", p.location.lat)
                    .put("lon", p.location.lon),
            )
        }
        prefs.edit().putString(KEY, arr.toString()).apply()
    }

    companion object {
        private const val PREFS = "destination_history"
        private const val KEY = "entries"
        private const val MAX_ENTRIES = 20
    }
}

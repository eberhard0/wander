package com.iameberhard.wander

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject

/** Favourites, history and the few settings, all in SharedPreferences. Nothing leaves the phone. */
class Places(context: Context) {

    private val prefs = context.applicationContext.getSharedPreferences("wander", Context.MODE_PRIVATE)

    var favourites: List<Place>
        get() = read("favourites")
        set(value) = write("favourites", value)

    var history: List<Place>
        get() = read("history")
        set(value) = write("history", value)

    fun addToHistory(place: Place) {
        history = (listOf(place) + history.filterNot { it.sameSpot(place) }).take(HISTORY_SIZE)
    }

    var speed: Double
        get() = prefs.getFloat("speed", Speeds.WALK.toFloat()).toDouble()
        set(value) = prefs.edit().putFloat("speed", value.toFloat()).apply()

    /** Where the running session last was; still set on launch only if that session never ended cleanly. */
    var active: LatLon?
        get() = if (prefs.contains("activeLat")) LatLon(double("activeLat"), double("activeLon")) else null
        set(value) = prefs.edit().apply {
            if (value == null) remove("activeLat").remove("activeLon")
            else putLong("activeLat", value.lat.toRawBits()).putLong("activeLon", value.lon.toRawBits())
        }.apply()

    /** Map centre and zoom to come back to. */
    var camera: Pair<LatLon, Double>?
        get() = if (prefs.contains("camLat")) LatLon(double("camLat"), double("camLon")) to double("camZoom") else null
        set(value) = prefs.edit().apply {
            if (value != null) {
                putLong("camLat", value.first.lat.toRawBits())
                putLong("camLon", value.first.lon.toRawBits())
                putLong("camZoom", value.second.toRawBits())
            }
        }.apply()

    private fun double(key: String) = Double.fromBits(prefs.getLong(key, 0))

    private fun read(key: String): List<Place> = try {
        val array = JSONArray(prefs.getString(key, "[]"))
        List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Place(o.getString("name"), o.getDouble("lat"), o.getDouble("lon"))
        }
    } catch (_: Exception) {
        emptyList()
    }

    private fun write(key: String, places: List<Place>) {
        val array = JSONArray()
        for (p in places) array.put(JSONObject().put("name", p.name).put("lat", p.lat).put("lon", p.lon))
        prefs.edit().putString(key, array.toString()).apply()
    }

    private companion object {
        const val HISTORY_SIZE = 25
    }
}

package com.iameberhard.wander

import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.util.Locale

/** The only network calls besides map tiles: OpenStreetMap's place search and a walking router. Blocking. */
object Net {
    // Both services ask for an identifying User-Agent.
    const val USER_AGENT = "Wander/1.0 (personal Android development tool)"

    fun search(query: String): List<Place> {
        val q = URLEncoder.encode(query, "UTF-8")
        val array = JSONArray(get("https://nominatim.openstreetmap.org/search?format=jsonv2&limit=8&q=$q"))
        return List(array.length()) { i ->
            val o = array.getJSONObject(i)
            Place(o.getString("display_name"), o.getString("lat").toDouble(), o.getString("lon").toDouble())
        }
    }

    /** Route along footpaths; falls back to a straight line when the router can't help. */
    fun walkingRoute(from: LatLon, to: LatLon): Route = try {
        val coordinates = String.format(Locale.US, "%.6f,%.6f;%.6f,%.6f", from.lon, from.lat, to.lon, to.lat)
        val json = JSONObject(
            get("https://routing.openstreetmap.de/routed-foot/route/v1/foot/$coordinates?overview=full&geometries=geojson"),
        )
        val route = json.getJSONArray("routes").getJSONObject(0)
        val line = route.getJSONObject("geometry").getJSONArray("coordinates")
        val points = List(line.length()) { i ->
            val pair = line.getJSONArray(i)
            LatLon(pair.getDouble(1), pair.getDouble(0))
        }
        if (points.size < 2) throw IOException("empty route")
        // Roads rarely start exactly at the pin: join the pin to the route at both ends.
        val full = listOf(from) + points + to
        Route(full, Geo.cumulative(full).last(), straightLine = false)
    } catch (_: Exception) {
        Route(listOf(from, to), Geo.distance(from, to), straightLine = true)
    }

    private fun get(url: String): String {
        val connection = URL(url).openConnection() as HttpURLConnection
        try {
            connection.setRequestProperty("User-Agent", USER_AGENT)
            connection.connectTimeout = 10_000
            connection.readTimeout = 15_000
            if (connection.responseCode !in 200..299) throw IOException("HTTP ${connection.responseCode}")
            return connection.inputStream.bufferedReader().use { it.readText() }
        } finally {
            connection.disconnect()
        }
    }
}

package com.iameberhard.wander

import java.util.Locale
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

data class LatLon(val lat: Double, val lon: Double) {
    fun label(): String = String.format(Locale.US, "%.5f, %.5f", lat, lon)
}

data class Place(val name: String, val lat: Double, val lon: Double) {
    val point get() = LatLon(lat, lon)

    // ~1 m: good enough to recognise "this is the favourite I saved".
    fun sameSpot(other: Place) = abs(lat - other.lat) < 1e-5 && abs(lon - other.lon) < 1e-5
}

data class Route(val points: List<LatLon>, val distance: Double, val straightLine: Boolean)

enum class Mode { IDLE, FIXED, WALKING }

data class Session(
    val mode: Mode = Mode.IDLE,
    val position: LatLon? = null,
    val route: List<LatLon> = emptyList(),
    val paused: Boolean = false,
    val reversed: Boolean = false,
    val arrived: Boolean = false,
    val speedMps: Double = Speeds.WALK,
    val travelled: Double = 0.0,
    val total: Double = 0.0,
    val error: String? = null,
) {
    val active get() = mode != Mode.IDLE
}

object Speeds {
    const val WALK = 5 / 3.6
    val presets = listOf("Walk" to WALK, "Jog" to 10 / 3.6, "Cycle" to 20 / 3.6, "Drive" to 50 / 3.6)
}

object Geo {
    private const val EARTH_RADIUS = 6_371_000.0

    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = Math.toRadians(b.lat - a.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val h = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(a.lat)) * cos(Math.toRadians(b.lat)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS * atan2(sqrt(h), sqrt(1 - h))
    }

    fun bearing(a: LatLon, b: LatLon): Float {
        val lat1 = Math.toRadians(a.lat)
        val lat2 = Math.toRadians(b.lat)
        val dLon = Math.toRadians(b.lon - a.lon)
        val y = sin(dLon) * cos(lat2)
        val x = cos(lat1) * sin(lat2) - sin(lat1) * cos(lat2) * cos(dLon)
        return ((Math.toDegrees(atan2(y, x)) + 360) % 360).toFloat()
    }

    /** Running distance from the first point to each point of [points]. */
    fun cumulative(points: List<LatLon>): DoubleArray {
        val out = DoubleArray(points.size)
        for (i in 1 until points.size) out[i] = out[i - 1] + distance(points[i - 1], points[i])
        return out
    }

    /** "12.5, 4.25", "12.5 4.25" or "12.5;4.25" typed into the search box. */
    fun parseCoordinates(text: String): LatLon? {
        val m = Regex("""^\s*(-?\d+(?:\.\d+)?)\s*[,; ]\s*(-?\d+(?:\.\d+)?)\s*$""").find(text) ?: return null
        val lat = m.groupValues[1].toDouble()
        val lon = m.groupValues[2].toDouble()
        return if (lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null
    }
}

fun formatDistance(metres: Double): String =
    if (metres < 1000) "${metres.roundToInt()} m" else String.format(Locale.US, "%.1f km", metres / 1000)

fun formatDuration(seconds: Double): String {
    val minutes = (seconds / 60).roundToInt()
    return when {
        minutes < 1 -> "under a minute"
        minutes < 60 -> "$minutes min"
        else -> "${minutes / 60} h ${minutes % 60} min"
    }
}

fun formatSpeed(mps: Double): String = "${(mps * 3.6).roundToInt()} km/h"

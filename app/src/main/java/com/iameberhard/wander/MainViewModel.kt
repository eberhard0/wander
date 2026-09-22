package com.iameberhard.wander

import android.Manifest
import android.annotation.SuppressLint
import android.app.Application
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val app get() = getApplication<Application>()
    val places = Places(application)

    var query by mutableStateOf("")
    var results by mutableStateOf<List<Place>>(emptyList())
        private set
    var searching by mutableStateOf(false)
        private set

    /** The pin the user is looking at, not yet acted on. */
    var selection by mutableStateOf<Place?>(null)
        private set

    /** Where the phone really is, while nothing is being mocked; null until a real fix arrives. */
    var realPosition by mutableStateOf<LatLon?>(null)
        private set
    var realAccuracyM by mutableStateOf<Float?>(null)
        private set

    private var tracking = false
    private val realListener = LocationListener { location -> acceptReal(location) }

    /**
     * Follows the real position while the app is on screen and no session is active. Nothing runs in
     * the background, and nothing at all without the location permission.
     */
    @SuppressLint("MissingPermission")
    fun startTrackingReal() {
        if (tracking) return
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return
        val manager = app.getSystemService(LocationManager::class.java)
        val providers = listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)
            .filter { runCatching { manager.isProviderEnabled(it) }.getOrDefault(false) }
        if (providers.isEmpty()) return
        // Something to show straight away, before the first fresh fix.
        providers.mapNotNull { runCatching { manager.getLastKnownLocation(it) }.getOrNull() }
            .filter { System.currentTimeMillis() - it.time < 5 * 60_000 }
            .maxByOrNull { it.time }?.let(::acceptReal)
        for (provider in providers) {
            runCatching { manager.requestLocationUpdates(provider, 2000L, 0f, realListener, Looper.getMainLooper()) }
        }
        tracking = true
    }

    fun stopTrackingReal() {
        if (!tracking) return
        app.getSystemService(LocationManager::class.java).removeUpdates(realListener)
        tracking = false
    }

    private fun acceptReal(location: Location) {
        // A leftover mock fix is exactly what we must not show as "you are here".
        if (location.isFromMockProvider) return
        val current = realAccuracyM
        // Keep a sharper recent fix over a vaguer new one (cell tower after GPS).
        if (current != null && location.hasAccuracy() && location.accuracy > current * 3 && location.accuracy > 100f) return
        realPosition = LatLon(location.latitude, location.longitude)
        realAccuracyM = if (location.hasAccuracy()) location.accuracy else null
    }

    fun clearReal() {
        realPosition = null
        realAccuracyM = null
    }

    /** A place the map should move to; the screen consumes it with [jumped]. */
    var jumpTo by mutableStateOf<Place?>(null)
        private set

    fun jumped() {
        jumpTo = null
    }

    /** A walking route waiting for "Start walk". */
    var preview by mutableStateOf<Route?>(null)
        private set
    var routing by mutableStateOf(false)
        private set

    var speed by mutableStateOf(places.speed)
        private set
    var favourites by mutableStateOf(places.favourites)
        private set
    var history by mutableStateOf(places.history)
        private set
    var message by mutableStateOf<String?>(null)
        private set

    /** Last known spot of a session that never ended cleanly (process killed, phone rebooted). */
    var interrupted by mutableStateOf<LatLon?>(null)
        private set

    private var searchJob: Job? = null
    private var routeJob: Job? = null

    init {
        if (!MockService.running) {
            interrupted = places.active
            // A killed session can leave its test providers behind, which keeps real GPS from coming back.
            MockService.removeTestProviders(application)
        }
    }

    /** Typed coordinates select the spot straight away and are returned so the map can go there. */
    fun search(): Place? {
        val text = query.trim()
        if (text.isEmpty()) return null
        message = null
        Geo.parseCoordinates(text)?.let {
            results = emptyList()
            val place = Place("Entered coordinates", it.lat, it.lon)
            select(place)
            jumpTo = place
            return place
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            try {
                val found = withContext(Dispatchers.IO) { Net.search(text) }
                if (found.isEmpty()) {
                    results = emptyList()
                    message = "Nothing found for \"$text\"."
                } else {
                    // Go to the best match at once; the others stay listed in case it was the wrong one.
                    select(found.first())
                    results = found
                    jumpTo = found.first()
                }
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = "Search failed – check the connection."
            } finally {
                searching = false
            }
        }
        return null
    }

    fun clearSearch() {
        searchJob?.cancel()
        query = ""
        results = emptyList()
    }

    fun select(place: Place) {
        routeJob?.cancel()
        routing = false
        preview = null
        results = emptyList()
        message = null
        selection = place
    }

    fun clearSelection() {
        routeJob?.cancel()
        routing = false
        preview = null
        selection = null
    }

    fun setFixed(place: Place) {
        // From here on the system reports the mock position; the real one is unknown until Restore.
        stopTrackingReal()
        clearReal()
        MockService.clearError()
        MockService.fixed(app, place.point)
        record(place)
        interrupted = null
        clearSelection()
    }

    /** Routes from wherever the phone currently claims to be, so this also redirects a walk in progress. */
    fun planWalk(to: Place) {
        val from = MockService.session.value.position ?: return
        routeJob?.cancel()
        routeJob = viewModelScope.launch {
            routing = true
            val route = withContext(Dispatchers.IO) { Net.walkingRoute(from, to.point) }
            routing = false
            preview = route
            message = if (route.straightLine) "No walking route found – using a straight line." else null
        }
    }

    fun startWalk() {
        val route = preview ?: return
        stopTrackingReal()
        clearReal()
        MockService.clearError()
        MockService.walk(app, route.points, speed)
        selection?.let(::record)
        clearSelection()
    }

    fun resumeInterrupted() {
        val point = interrupted ?: return
        setFixed(Place("Resumed session", point.lat, point.lon))
    }

    fun dismissInterrupted() {
        places.active = null
        interrupted = null
    }

    fun restoreRealLocation() {
        MockService.stop(app)
        clearSelection()
        clearReal()
        wakeRealLocation()
    }

    /**
     * After a session the GPS is cold and the system still has our last mock fix cached, so other apps
     * wait until somebody asks for a fresh one. Ask straight away, on every provider, so that wait is short.
     */
    @SuppressLint("MissingPermission")
    private fun wakeRealLocation() {
        if (app.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            message = "Real location restored. Tip: tap the location button once and allow access – Wander can then bring the real fix back faster."
            return
        }
        message = "Waiting for the real location…"
        viewModelScope.launch {
            // Let the service take its test providers down first, or we'd just be handed the mock fix back.
            delay(1000)
            val manager = app.getSystemService(LocationManager::class.java)
            var answered = false
            for (provider in listOf("fused", LocationManager.NETWORK_PROVIDER, LocationManager.GPS_PROVIDER)) {
                if (!runCatching { manager.isProviderEnabled(provider) }.getOrDefault(false)) continue
                manager.getCurrentLocation(provider, null, app.mainExecutor) { fix ->
                    @Suppress("DEPRECATION")
                    if (fix != null && !fix.isFromMockProvider && !answered && !MockService.session.value.active) {
                        answered = true
                        acceptReal(fix)
                        message = "Real location is back (±${fix.accuracy.toInt()} m)."
                    }
                }
            }
        }
    }

    fun changeSpeed(mps: Double) {
        speed = mps
        places.speed = mps
        MockService.speed(app, mps)
    }

    fun isFavourite(place: Place) = favourites.any { it.sameSpot(place) }

    fun addFavourite(place: Place, name: String) {
        val named = place.copy(name = name.trim().ifEmpty { place.name })
        favourites = listOf(named) + favourites.filterNot { it.sameSpot(place) }
        places.favourites = favourites
        if (selection?.sameSpot(place) == true) selection = named
    }

    fun removeFavourite(place: Place) {
        favourites = favourites.filterNot { it.sameSpot(place) }
        places.favourites = favourites
    }

    fun clearHistory() {
        history = emptyList()
        places.history = history
    }

    fun show(text: String) {
        message = text
    }

    override fun onCleared() = stopTrackingReal()

    private fun record(place: Place) {
        places.addToHistory(place)
        history = places.history
    }
}

package com.iameberhard.wander

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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

    fun search() {
        val text = query.trim()
        if (text.isEmpty()) return
        message = null
        Geo.parseCoordinates(text)?.let {
            results = emptyList()
            return select(Place("Entered coordinates", it.lat, it.lon))
        }
        searchJob?.cancel()
        searchJob = viewModelScope.launch {
            searching = true
            try {
                results = withContext(Dispatchers.IO) { Net.search(text) }
                if (results.isEmpty()) message = "Nothing found for \"$text\"."
            } catch (e: Exception) {
                if (e is kotlinx.coroutines.CancellationException) throw e
                message = "Search failed – check the connection."
            } finally {
                searching = false
            }
        }
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

    private fun record(place: Place) {
        places.addToHistory(place)
        history = places.history
    }
}

package com.iameberhard.wander

import android.content.Context
import android.graphics.Color
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraPosition
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.Style
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.LineLayer
import org.maplibre.android.style.layers.Property
import org.maplibre.android.style.layers.PropertyFactory.circleColor
import org.maplibre.android.style.layers.PropertyFactory.circlePitchAlignment
import org.maplibre.android.style.layers.PropertyFactory.circleRadius
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeColor
import org.maplibre.android.style.layers.PropertyFactory.circleStrokeWidth
import org.maplibre.android.style.layers.PropertyFactory.iconAllowOverlap
import org.maplibre.android.style.layers.PropertyFactory.iconAnchor
import org.maplibre.android.style.layers.PropertyFactory.iconIgnorePlacement
import org.maplibre.android.style.layers.PropertyFactory.iconImage
import org.maplibre.android.style.layers.PropertyFactory.lineCap
import org.maplibre.android.style.layers.PropertyFactory.lineColor
import org.maplibre.android.style.layers.PropertyFactory.lineJoin
import org.maplibre.android.style.layers.PropertyFactory.lineWidth
import org.maplibre.android.style.layers.SymbolLayer
import org.maplibre.android.style.sources.GeoJsonSource
import org.maplibre.geojson.Feature
import org.maplibre.geojson.FeatureCollection
import org.maplibre.geojson.LineString
import org.maplibre.geojson.Point

/**
 * Owns the MapLibre view and the three things drawn on it: the pin, the mock position, the route.
 * Vector tiles come from OpenFreeMap (no key); the bundled styles label everything in English.
 *
 * The map and its style load asynchronously – and the style is swapped when the system theme changes – so
 * what should be shown is kept here and redrawn whenever a style becomes ready.
 */
class MapHolder(private val context: Context, private val places: Places, onTap: (LatLon) -> Unit) {

    val view: MapView

    private var map: MapLibreMap? = null
    private var style: Style? = null
    private var dark = false
    private var shownDark: Boolean? = null
    private var pin: LatLon? = null
    private var mock: LatLon? = null
    private var real: LatLon? = null
    private var realAccuracyM: Float? = null
    private var route: List<LatLon> = emptyList()
    /** A camera move asked for before the map was ready. */
    private var pendingCamera: (() -> Unit)? = null

    private val padding = (96 * context.resources.displayMetrics.density).toInt()
    private var started = false
    private var resumed = false
    private var destroyed = false

    val lifecycleObserver = LifecycleEventObserver { _, event ->
        when (event) {
            Lifecycle.Event.ON_START -> start()
            Lifecycle.Event.ON_RESUME -> resume()
            Lifecycle.Event.ON_PAUSE -> pause()
            Lifecycle.Event.ON_STOP -> stop()
            else -> Unit
        }
    }

    init {
        MapLibre.getInstance(context)
        view = MapView(context)
        view.onCreate(null)
        view.getMapAsync { ready ->
            map = ready
            ready.uiSettings.apply {
                setLogoEnabled(false)
                setAttributionEnabled(false) // credited in our own corner text instead
                setCompassEnabled(false)
                setRotateGesturesEnabled(false)
                setTiltGesturesEnabled(false)
            }
            val (centre, zoom) = places.camera ?: (LatLon(50.0, 10.0) to 3.5)
            ready.cameraPosition = CameraPosition.Builder().target(LatLng(centre.lat, centre.lon)).zoom(zoom).build()
            ready.addOnMapClickListener { point ->
                onTap(LatLon(point.latitude, point.longitude))
                true
            }
            ready.addOnMapLongClickListener { point ->
                onTap(LatLon(point.latitude, point.longitude))
                true
            }
            loadStyle()
        }
    }

    fun setDark(dark: Boolean) {
        this.dark = dark
        loadStyle()
    }

    fun showPin(point: LatLon?) {
        pin = point
        draw()
    }

    fun showMock(point: LatLon?) {
        mock = point
        draw()
    }

    fun showReal(point: LatLon?, accuracyM: Float?) {
        real = point
        realAccuracyM = accuracyM
        draw()
    }

    fun showRoute(points: List<LatLon>, fit: Boolean) {
        route = points
        draw()
        if (fit && points.size >= 2) {
            whenReady { map ->
                try {
                    val bounds = LatLngBounds.Builder().includes(points.map { LatLng(it.lat, it.lon) }).build()
                    map.animateCamera(CameraUpdateFactory.newLatLngBounds(bounds, padding), 600)
                } catch (_: RuntimeException) {
                    // Start and end in one spot: no box to fit.
                }
            }
        }
    }

    fun centreOn(point: LatLon) {
        whenReady { map ->
            val target = LatLng(point.lat, point.lon)
            val zoom = maxOf(map.cameraPosition.zoom, 15.0)
            val update = CameraUpdateFactory.newLatLngZoom(target, zoom)
            // A long fly-to gets dropped when the keyboard closing resizes the map mid-flight (which is
            // exactly when a search result is picked), so far-away places are jumped to instead.
            val far = map.cameraPosition.target?.let { Geo.distance(LatLon(it.latitude, it.longitude), point) > 20_000 } ?: true
            if (far) map.moveCamera(update) else map.easeCamera(update, 600)
        }
    }

    fun saveCamera() {
        val camera = map?.cameraPosition ?: return
        val target = camera.target ?: return
        places.camera = LatLon(target.latitude, target.longitude) to camera.zoom
    }

    fun destroy() {
        pause()
        stop()
        if (!destroyed) {
            destroyed = true
            style = null
            map = null
            view.onDestroy()
        }
    }

    private fun start() {
        if (!started && !destroyed) { started = true; view.onStart() }
    }

    private fun resume() {
        if (!resumed && !destroyed) { resumed = true; view.onResume() }
    }

    private fun pause() {
        if (resumed) { resumed = false; view.onPause() }
    }

    private fun stop() {
        if (started) { started = false; view.onStop() }
    }

    private fun loadStyle() {
        val ready = map ?: return
        if (shownDark == dark) return
        shownDark = dark
        style = null
        val json = context.assets.open(if (dark) "map_dark.json" else "map_liberty.json").bufferedReader().use { it.readText() }
        ready.setStyle(Style.Builder().fromJson(json)) { loaded ->
            // A new style starts empty: our layers have to be added again every time.
            loaded.addSource(GeoJsonSource(ROUTE))
            loaded.addLayer(
                LineLayer(ROUTE, ROUTE).withProperties(
                    lineColor(Color.rgb(0x1E, 0x88, 0xE5)), lineWidth(5f),
                    lineCap(Property.LINE_CAP_ROUND), lineJoin(Property.LINE_JOIN_ROUND),
                ),
            )
            // Real position: the familiar blue dot, with its accuracy as a translucent disc underneath.
            loaded.addSource(GeoJsonSource(REAL))
            loaded.addLayer(
                CircleLayer(REAL_ACCURACY, REAL).withProperties(
                    circleRadius(
                        Expression.interpolate(
                            Expression.exponential(2f), Expression.zoom(),
                            Expression.stop(0, 0f),
                            Expression.stop(22, Expression.get("radius_px")),
                        ),
                    ),
                    circleColor(Color.argb(40, 0x42, 0x85, 0xF4)), circleStrokeWidth(0f),
                    circlePitchAlignment(Property.CIRCLE_PITCH_ALIGNMENT_MAP),
                ),
            )
            loaded.addLayer(
                CircleLayer(REAL, REAL).withProperties(
                    circleRadius(8f), circleColor(Color.rgb(0x42, 0x85, 0xF4)),
                    circleStrokeWidth(3f), circleStrokeColor(Color.WHITE),
                ),
            )
            loaded.addSource(GeoJsonSource(MOCK))
            loaded.addLayer(
                CircleLayer(MOCK, MOCK).withProperties(
                    circleRadius(9f), circleColor(Color.rgb(0x3D, 0xDC, 0x97)),
                    circleStrokeWidth(3f), circleStrokeColor(Color.WHITE),
                ),
            )
            ContextCompat.getDrawable(context, R.drawable.ic_pin)?.let { loaded.addImage(PIN, it) }
            loaded.addSource(GeoJsonSource(PIN))
            loaded.addLayer(
                SymbolLayer(PIN, PIN).withProperties(
                    iconImage(PIN), iconAnchor(Property.ICON_ANCHOR_BOTTOM),
                    iconAllowOverlap(true), iconIgnorePlacement(true),
                ),
            )
            style = loaded
            draw()
            pendingCamera?.invoke()
            pendingCamera = null
        }
    }

    private fun whenReady(action: (MapLibreMap) -> Unit) {
        val ready = map
        if (ready != null && style != null) action(ready) else pendingCamera = { map?.let(action) }
    }

    private fun draw() {
        val loaded = style ?: return
        loaded.getSourceAs<GeoJsonSource>(ROUTE)?.setGeoJson(
            if (route.size < 2) NOTHING
            else FeatureCollection.fromFeature(Feature.fromGeometry(LineString.fromLngLats(route.map { Point.fromLngLat(it.lon, it.lat) }))),
        )
        loaded.getSourceAs<GeoJsonSource>(REAL)?.setGeoJson(realFeature())
        loaded.getSourceAs<GeoJsonSource>(MOCK)?.setGeoJson(point(mock))
        loaded.getSourceAs<GeoJsonSource>(PIN)?.setGeoJson(point(pin))
    }

    /** The dot, carrying its accuracy radius in pixels at zoom 22 (where 1 px ≈ 0.037 m at the equator). */
    private fun realFeature(): FeatureCollection {
        val at = real ?: return NOTHING
        val feature = Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat))
        val metresPerPixelAtZ22 = 0.0373 * Math.cos(Math.toRadians(at.lat))
        feature.addNumberProperty("radius_px", ((realAccuracyM ?: 0f) / metresPerPixelAtZ22).toFloat())
        return FeatureCollection.fromFeature(feature)
    }

    private fun point(at: LatLon?): FeatureCollection =
        if (at == null) NOTHING else FeatureCollection.fromFeature(Feature.fromGeometry(Point.fromLngLat(at.lon, at.lat)))

    private companion object {
        const val ROUTE = "wander-route"
        const val REAL = "wander-real"
        const val REAL_ACCURACY = "wander-real-accuracy"
        const val MOCK = "wander-mock"
        const val PIN = "wander-pin"
        val NOTHING: FeatureCollection = FeatureCollection.fromFeatures(emptyList<Feature>())
    }
}

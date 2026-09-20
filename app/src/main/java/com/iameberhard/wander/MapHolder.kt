package com.iameberhard.wander

import android.content.Context
import android.graphics.Color
import android.graphics.Paint
import android.graphics.drawable.GradientDrawable
import org.osmdroid.config.Configuration
import org.osmdroid.events.MapEventsReceiver
import org.osmdroid.tileprovider.tilesource.TileSourceFactory
import org.osmdroid.util.BoundingBox
import org.osmdroid.util.GeoPoint
import org.osmdroid.views.CustomZoomButtonsController
import org.osmdroid.views.MapView
import org.osmdroid.views.overlay.MapEventsOverlay
import org.osmdroid.views.overlay.Marker
import org.osmdroid.views.overlay.Polyline
import org.osmdroid.views.overlay.TilesOverlay
import java.io.File

/** Owns the OpenStreetMap view and the three things drawn on it: the pin, the mock position, the route. */
class MapHolder(context: Context, private val places: Places, onTap: (LatLon) -> Unit) {

    val view: MapView

    private val route: Polyline
    private val pin: Marker
    private val mock: Marker

    init {
        Configuration.getInstance().apply {
            userAgentValue = Net.USER_AGENT
            // Keep the tile cache in the app's own cache dir so it needs no storage permission.
            osmdroidBasePath = File(context.cacheDir, "osmdroid")
            osmdroidTileCache = File(context.cacheDir, "osmdroid/tiles")
        }

        view = MapView(context).apply {
            setTileSource(TileSourceFactory.MAPNIK)
            setMultiTouchControls(true)
            zoomController.setVisibility(CustomZoomButtonsController.Visibility.NEVER)
            isTilesScaledToDpi = true
            setMinZoomLevel(3.0)
            isVerticalMapRepetitionEnabled = false
            setScrollableAreaLimitLatitude(MapView.getTileSystem().maxLatitude, MapView.getTileSystem().minLatitude, 0)
        }

        val density = context.resources.displayMetrics.density

        route = Polyline(view).apply {
            outlinePaint.color = Color.rgb(0x1E, 0x88, 0xE5)
            outlinePaint.strokeWidth = 5 * density
            outlinePaint.strokeCap = Paint.Cap.ROUND
            outlinePaint.strokeJoin = Paint.Join.ROUND
            setOnClickListener { _, _, _ -> false }
        }
        pin = Marker(view).apply {
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_BOTTOM)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> false }
            isEnabled = false
        }
        mock = Marker(view).apply {
            setIcon(GradientDrawable().apply {
                shape = GradientDrawable.OVAL
                setColor(Color.rgb(0x3D, 0xDC, 0x97))
                setStroke((3 * density).toInt(), Color.WHITE)
                setSize((22 * density).toInt(), (22 * density).toInt())
            })
            setAnchor(Marker.ANCHOR_CENTER, Marker.ANCHOR_CENTER)
            setInfoWindow(null)
            setOnMarkerClickListener { _, _ -> false }
            isEnabled = false
        }

        val taps = MapEventsOverlay(object : MapEventsReceiver {
            override fun singleTapConfirmedHelper(p: GeoPoint): Boolean {
                onTap(LatLon(p.latitude, p.longitude))
                return true
            }

            override fun longPressHelper(p: GeoPoint): Boolean {
                onTap(LatLon(p.latitude, p.longitude))
                return true
            }
        })
        view.overlays.addAll(listOf(taps, route, mock, pin))

        val (centre, zoom) = places.camera ?: (LatLon(50.0, 10.0) to 4.5)
        view.controller.setZoom(zoom)
        view.controller.setCenter(GeoPoint(centre.lat, centre.lon))
    }

    /** OSM's standard tiles only come in light; inverting them is a decent night map. */
    fun setDark(dark: Boolean) {
        view.overlayManager.tilesOverlay.setColorFilter(if (dark) TilesOverlay.INVERT_COLORS else null)
        view.invalidate()
    }

    fun showPin(point: LatLon?) = place(pin, point)

    fun showMock(point: LatLon?) = place(mock, point)

    fun showRoute(points: List<LatLon>, fit: Boolean) {
        val geoPoints = points.map { GeoPoint(it.lat, it.lon) }
        route.setPoints(geoPoints)
        if (fit && points.size >= 2) {
            val box = BoundingBox.fromGeoPoints(geoPoints)
            view.post { view.zoomToBoundingBox(box.increaseByScale(1.6f), true) }
        }
        view.invalidate()
    }

    fun centreOn(point: LatLon) {
        view.controller.animateTo(GeoPoint(point.lat, point.lon), maxOf(view.zoomLevelDouble, 16.0), 600L)
    }

    fun saveCamera() {
        val centre = view.mapCenter
        places.camera = LatLon(centre.latitude, centre.longitude) to view.zoomLevelDouble
    }

    private fun place(marker: Marker, point: LatLon?) {
        marker.isEnabled = point != null
        if (point != null) marker.position = GeoPoint(point.lat, point.lon)
        view.invalidate()
    }
}

package com.iameberhard.wander

import android.app.AppOpsManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.location.Location
import android.location.LocationManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import androidx.core.app.ServiceCompat
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Feeds the system's test location providers once a second. It is a foreground service because the
 * whole point is that the feed keeps going while a different app is on screen being tested.
 * Nothing runs when no session is active.
 */
class MockService : Service() {

    private val locationManager by lazy { getSystemService(LocationManager::class.java) }
    private val places by lazy { Places(this) }
    private val handler = Handler(Looper.getMainLooper())

    private var providers = emptyList<String>()
    private var route = emptyList<LatLon>()
    private var cumulative = DoubleArray(0)
    private var bearing = 0f
    private var lastTick = 0L
    private var ticks = 0

    private val ticker = object : Runnable {
        override fun run() {
            step()
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        running = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen for every start, whatever the action turns out to be.
        ServiceCompat.startForeground(
            this, NOTIFICATION_ID, notification(),
            if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE else 0,
        )
        when (intent?.action) {
            ACTION_FIXED -> startFixed(LatLon(intent.getDoubleExtra(EXTRA_LAT, 0.0), intent.getDoubleExtra(EXTRA_LON, 0.0)))
            ACTION_WALK -> startWalk(pendingRoute, intent.getDoubleExtra(EXTRA_SPEED, Speeds.WALK))
            ACTION_PAUSE -> update { it.copy(paused = true) }
            ACTION_RESUME -> update { it.copy(paused = false) }
            ACTION_REVERSE -> update { it.copy(reversed = !it.reversed, arrived = false, paused = false) }
            ACTION_SPEED -> update { it.copy(speedMps = intent.getDoubleExtra(EXTRA_SPEED, Speeds.WALK)) }
            else -> shutdown()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacks(ticker)
        removeProviders()
        if (state.value.active) state.value = Session()
        running = false
        super.onDestroy()
    }

    private fun startFixed(point: LatLon) {
        if (!ensureProviders()) return
        route = emptyList()
        cumulative = DoubleArray(0)
        state.value = Session(mode = Mode.FIXED, position = point, speedMps = state.value.speedMps)
        begin()
    }

    private fun startWalk(points: List<LatLon>, speed: Double) {
        if (points.size < 2) return startFixed(points.firstOrNull() ?: return shutdown())
        if (!ensureProviders()) return
        route = points
        cumulative = Geo.cumulative(points)
        bearing = Geo.bearing(points[0], points[1])
        state.value = Session(
            mode = Mode.WALKING, position = points.first(), route = points,
            speedMps = speed, total = cumulative.last(),
        )
        begin()
    }

    private fun begin() {
        handler.removeCallbacks(ticker)
        lastTick = SystemClock.elapsedRealtime()
        refreshNotification()
        ticker.run()
    }

    private fun update(change: (Session) -> Session) {
        if (!state.value.active) return shutdown()
        state.value = change(state.value)
        refreshNotification()
    }

    private fun step() {
        val now = SystemClock.elapsedRealtime()
        val dt = (now - lastTick) / 1000.0
        lastTick = now

        var s = state.value
        val moving = s.mode == Mode.WALKING && !s.paused && !s.arrived
        if (moving) {
            val travelled = (s.travelled + s.speedMps * dt * if (s.reversed) -1 else 1).coerceIn(0.0, s.total)
            val arrived = if (s.reversed) travelled <= 0.0 else travelled >= s.total
            s = s.copy(travelled = travelled, arrived = arrived, position = locate(travelled, s.reversed))
            state.value = s
            if (arrived) refreshNotification()
        }

        val point = s.position ?: return
        push(point, if (moving) s.speedMps.toFloat() else 0f)

        // Remember roughly where we are so an interrupted session can be offered back on next launch.
        if (ticks++ % 10 == 0) places.active = point
    }

    /** Point [distance] metres along the route; also keeps [bearing] pointing the way we're heading. */
    private fun locate(distance: Double, reversed: Boolean): LatLon {
        var i = cumulative.binarySearch(distance)
        if (i < 0) i = -i - 2
        i = i.coerceIn(0, route.size - 2)
        val a = route[i]
        val b = route[i + 1]
        val segment = cumulative[i + 1] - cumulative[i]
        val f = if (segment <= 0) 0.0 else ((distance - cumulative[i]) / segment).coerceIn(0.0, 1.0)
        if (segment > 0) bearing = if (reversed) Geo.bearing(b, a) else Geo.bearing(a, b)
        return LatLon(a.lat + (b.lat - a.lat) * f, a.lon + (b.lon - a.lon) * f)
    }

    private fun push(point: LatLon, speed: Float) {
        for (provider in providers) {
            val location = Location(provider).apply {
                latitude = point.lat
                longitude = point.lon
                altitude = 0.0
                accuracy = 3f
                this.speed = speed
                bearing = this@MockService.bearing
                verticalAccuracyMeters = 3f
                speedAccuracyMetersPerSecond = 0.1f
                bearingAccuracyDegrees = 1f
                time = System.currentTimeMillis()
                elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            }
            try {
                locationManager.setTestProviderLocation(provider, location)
            } catch (e: SecurityException) {
                // The user picked a different mock app (or none) while we were running.
                return fail()
            } catch (_: IllegalArgumentException) {
            }
        }
    }

    /** Registers the test providers. False (and the session ends) when we aren't the mock location app. */
    private fun ensureProviders(): Boolean {
        if (providers.isNotEmpty()) return true
        val added = mutableListOf<String>()
        for (provider in PROVIDERS) {
            try {
                runCatching { locationManager.removeTestProvider(provider) }
                // The two ints are POWER_USAGE_LOW and ACCURACY_FINE.
                locationManager.addTestProvider(provider, false, false, false, false, true, true, true, 1, 1)
                locationManager.setTestProviderEnabled(provider, true)
                added += provider
            } catch (e: SecurityException) {
                fail()
                return false
            } catch (_: IllegalArgumentException) {
                // This device doesn't let that provider be mocked; the others still work.
            }
        }
        providers = added
        if (added.isEmpty()) fail()
        return added.isNotEmpty()
    }

    private fun removeProviders() {
        removeTestProviders(this)
        providers = emptyList()
    }

    private fun fail() {
        shutdown()
        state.value = Session(error = "Wander isn't selected as the mock location app.")
    }

    private fun shutdown() {
        handler.removeCallbacks(ticker)
        removeProviders()
        places.active = null
        state.value = Session(speedMps = state.value.speedMps)
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun refreshNotification() {
        getSystemService(NotificationManager::class.java).notify(NOTIFICATION_ID, notification())
    }

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL) == null) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL, "Active mock location", NotificationManager.IMPORTANCE_LOW),
            )
        }
        val s = state.value
        val title = when {
            s.mode == Mode.WALKING && s.arrived -> "Walk finished – holding position"
            s.mode == Mode.WALKING && s.paused -> "Walk paused"
            s.mode == Mode.WALKING -> "Simulating a walk"
            else -> "Reporting a fixed location"
        }
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE,
        )
        val stop = PendingIntent.getService(
            this, 1, Intent(this, MockService::class.java).setAction(ACTION_STOP), PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL)
            .setSmallIcon(R.drawable.ic_stat_pin)
            .setContentTitle(title)
            .setContentText("Every app on this phone sees the mock location.")
            .setContentIntent(open)
            .setOngoing(true)
            .addAction(Notification.Action.Builder(null, "Restore real location", stop).build())
            .build()
    }

    companion object {
        private const val TICK_MS = 1000L
        private const val NOTIFICATION_ID = 1
        private const val CHANNEL = "mock"
        private val PROVIDERS = listOf(LocationManager.GPS_PROVIDER, LocationManager.NETWORK_PROVIDER, "fused")

        private const val ACTION_FIXED = "fixed"
        private const val ACTION_WALK = "walk"
        private const val ACTION_PAUSE = "pause"
        private const val ACTION_RESUME = "resume"
        private const val ACTION_REVERSE = "reverse"
        private const val ACTION_SPEED = "speed"
        private const val ACTION_STOP = "stop"
        private const val EXTRA_LAT = "lat"
        private const val EXTRA_LON = "lon"
        private const val EXTRA_SPEED = "speed"

        private val state = MutableStateFlow(Session())
        val session: StateFlow<Session> get() = state

        @Volatile
        var running = false
            private set

        // Routes can run to thousands of points; same process, so no need to squeeze them through an Intent.
        private var pendingRoute = emptyList<LatLon>()

        fun isMockApp(context: Context): Boolean {
            val ops = context.getSystemService(AppOpsManager::class.java)
            return ops.unsafeCheckOpNoThrow(
                AppOpsManager.OPSTR_MOCK_LOCATION, Process.myUid(), context.packageName,
            ) == AppOpsManager.MODE_ALLOWED
        }

        /** Test providers outlive a killed process and would leave the phone stuck; safe to call any time. */
        fun removeTestProviders(context: Context) {
            val manager = context.getSystemService(LocationManager::class.java)
            for (provider in PROVIDERS) runCatching { manager.removeTestProvider(provider) }
        }

        fun clearError() {
            if (state.value.error != null) state.value = state.value.copy(error = null)
        }

        fun fixed(context: Context, point: LatLon) = context.startForegroundService(
            command(context, ACTION_FIXED).putExtra(EXTRA_LAT, point.lat).putExtra(EXTRA_LON, point.lon),
        )

        fun walk(context: Context, points: List<LatLon>, speed: Double) {
            pendingRoute = points
            context.startForegroundService(command(context, ACTION_WALK).putExtra(EXTRA_SPEED, speed))
        }

        fun pause(context: Context) = send(context, command(context, ACTION_PAUSE))
        fun resume(context: Context) = send(context, command(context, ACTION_RESUME))
        fun reverse(context: Context) = send(context, command(context, ACTION_REVERSE))
        fun speed(context: Context, mps: Double) = send(context, command(context, ACTION_SPEED).putExtra(EXTRA_SPEED, mps))
        fun stop(context: Context) = send(context, command(context, ACTION_STOP))

        private fun command(context: Context, action: String) = Intent(context, MockService::class.java).setAction(action)

        private fun send(context: Context, intent: Intent) {
            if (running) context.startService(intent)
        }
    }
}

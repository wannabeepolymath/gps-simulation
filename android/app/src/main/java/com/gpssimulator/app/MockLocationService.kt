package com.gpssimulator.app

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
import android.location.provider.ProviderProperties
import android.os.Build
import android.os.Bundle
import android.os.IBinder
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.File

class MockLocationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var emitJob: Job? = null
    private var providerAdded = false
    @Volatile private var activeProviders: List<String> = emptyList()
    private lateinit var locationManager: LocationManager

    @Volatile private var paused: Boolean = false
    @Volatile private var reverse: Boolean = false
    private var lastFileName: String = ""

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
        createNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopAll()
                return START_NOT_STICKY
            }
            ACTION_TOGGLE_PAUSE -> {
                paused = !paused
                return START_STICKY
            }
            ACTION_TOGGLE_DIRECTION -> {
                reverse = !reverse
                return START_STICKY
            }
        }

        val path = intent?.getStringExtra(EXTRA_GPX_PATH)
        if (path == null) {
            stopAll()
            return START_NOT_STICKY
        }

        startInForeground(File(path).name)

        val points = try {
            File(path).inputStream().use { GpxParser.parse(it) }
        } catch (e: Exception) {
            ServiceState.set(RunState.Failed("Parse failed: ${e.message}"))
            stopAll()
            return START_NOT_STICKY
        }

        if (!setupProvider()) {
            ServiceState.set(
                RunState.Failed(
                    "Mock location not allowed. Pick this app in Developer Options → Select mock location app."
                )
            )
            stopAll()
            return START_NOT_STICKY
        }

        paused = false
        reverse = false
        lastFileName = File(path).name
        emitJob?.cancel()
        emitJob = scope.launch { runEmission(lastFileName, points) }
        return START_STICKY
    }

    private suspend fun runEmission(fileName: String, points: List<TrackPoint>) {
        val totalSeconds = (points.last().time.epochSecond - points.first().time.epochSecond).coerceAtLeast(1)

        // Virtual playback position along the route, in seconds from t0.
        // Advances forward or backward each tick based on the current direction
        // flag; holds at either endpoint.
        var simSec = 0.0
        var prevLat = points.first().lat
        var prevLon = points.first().lon
        var prevWall = SystemClock.elapsedRealtime()

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val dtWallSec = ((now - prevWall) / 1000.0).coerceAtLeast(0.0)
            prevWall = now

            if (!paused) {
                simSec += if (reverse) -dtWallSec else dtWallSec
            }
            val atEndpoint = simSec >= totalSeconds || simSec <= 0.0
            simSec = simSec.coerceIn(0.0, totalSeconds.toDouble())

            val pt = interpolateAtSec(points, simSec)

            val distM = Geo.haversineMeters(prevLat, prevLon, pt.lat, pt.lon)
            val speed = if (paused || atEndpoint || dtWallSec <= 0.0) 0f
            else (distM / dtWallSec).toFloat()
            prevLat = pt.lat
            prevLon = pt.lon

            val loc = buildLocation(pt, speed)
            try {
                // Push the same fix to every mocked provider. Mocking only GPS lets
                // the fused provider (what tracking apps actually read) blend in the
                // device's real NETWORK position, so the recorded track jumps between
                // real and simulated coordinates.
                for (provider in activeProviders) {
                    loc.provider = provider
                    locationManager.setTestProviderLocation(provider, loc)
                }
            } catch (e: SecurityException) {
                ServiceState.set(RunState.Failed("Lost mock location permission: ${e.message}"))
                stopAll()
                return
            }

            ServiceState.set(
                RunState.Running(
                    fileName = fileName,
                    elapsedSeconds = simSec.toLong().coerceIn(0L, totalSeconds),
                    totalSeconds = totalSeconds,
                    holdingLastPoint = atEndpoint,
                    paused = paused,
                    direction = if (reverse) Direction.Reverse else Direction.Forward,
                )
            )

            delay(1000L)
        }
    }

    /** Interpolate the point at virtualSec into the timeline (clamped). */
    private fun interpolateAtSec(points: List<TrackPoint>, virtualSec: Double): TrackPoint {
        val t0 = points.first().time.epochSecond.toDouble()
        val tTarget = t0 + virtualSec
        if (tTarget >= points.last().time.epochSecond) return points.last()
        if (tTarget <= t0) return points.first()
        var lo = 0
        var hi = points.size - 1
        while (lo + 1 < hi) {
            val mid = (lo + hi) / 2
            if (points[mid].time.epochSecond.toDouble() <= tTarget) lo = mid else hi = mid
        }
        val a = points[lo]
        val b = points[hi]
        val span = (b.time.epochSecond - a.time.epochSecond).toDouble().coerceAtLeast(0.001)
        val f = ((tTarget - a.time.epochSecond) / span).coerceIn(0.0, 1.0)
        return TrackPoint(
            lat = a.lat + (b.lat - a.lat) * f,
            lon = a.lon + (b.lon - a.lon) * f,
            ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else (a.ele ?: b.ele),
            time = a.time,
        )
    }

    private fun buildLocation(pt: TrackPoint, speed: Float): Location {
        val loc = Location(LocationManager.GPS_PROVIDER).apply {
            latitude = pt.lat
            longitude = pt.lon
            if (pt.ele != null) altitude = pt.ele
            accuracy = 3.0f
            this.speed = speed
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                speedAccuracyMetersPerSecond = 0.5f
                bearingAccuracyDegrees = 5.0f
                verticalAccuracyMeters = 3.0f
            }
            time = System.currentTimeMillis()
            elapsedRealtimeNanos = SystemClock.elapsedRealtimeNanos()
            extras = Bundle().apply { putInt("satellites", 12) }
        }
        return loc
    }

    private fun setupProvider(): Boolean {
        // GPS is mandatory; if it can't be mocked we have no permission at all.
        // NETWORK is best-effort but normally succeeds — mocking it too stops the
        // fused provider from leaking the device's real position into tracking apps.
        if (!addProvider(LocationManager.GPS_PROVIDER, satellite = true)) return false
        val providers = mutableListOf(LocationManager.GPS_PROVIDER)
        if (addProvider(LocationManager.NETWORK_PROVIDER, satellite = false)) {
            providers.add(LocationManager.NETWORK_PROVIDER)
        }
        // Some apps (API 31+) read the platform fused provider directly. Mock it too,
        // best-effort — devices that reserve "fused" throw and we just skip it, falling
        // back to gps+network (which already covers Play Services' fused client).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            addProvider(LocationManager.FUSED_PROVIDER, satellite = false)
        ) {
            providers.add(LocationManager.FUSED_PROVIDER)
        }
        activeProviders = providers.toList()
        providerAdded = true
        return true
    }

    private fun addProvider(provider: String, satellite: Boolean): Boolean {
        return try {
            try { locationManager.removeTestProvider(provider) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasCellRequirement(false)
                    .setHasSatelliteRequirement(satellite)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(provider, props)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    provider,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ satellite,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    /* powerRequirement = */ 1,
                    /* accuracy = */ 1,
                )
            }
            locationManager.setTestProviderEnabled(provider, true)
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun tearDownProvider() {
        if (!providerAdded) return
        for (provider in activeProviders) {
            try {
                locationManager.setTestProviderEnabled(provider, false)
                locationManager.removeTestProvider(provider)
            } catch (_: Exception) {}
        }
        activeProviders = emptyList()
        providerAdded = false
    }

    private fun stopAll() {
        emitJob?.cancel()
        emitJob = null
        tearDownProvider()
        ServiceState.set(RunState.Idle)
        stopForeground(STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    override fun onDestroy() {
        emitJob?.cancel()
        scope.cancel()
        tearDownProvider()
        ServiceState.set(RunState.Idle)
        super.onDestroy()
    }

    private fun startInForeground(fileName: String) {
        val notif = buildNotification(fileName)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    private fun buildNotification(fileName: String): Notification {
        val stopIntent = Intent(this, MockLocationService::class.java).apply { action = ACTION_STOP }
        val stopPi = PendingIntent.getService(
            this, 0, stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }
        val openPi = PendingIntent.getActivity(
            this, 0, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(fileName)
            .setOngoing(true)
            .setContentIntent(openPi)
            .addAction(0, getString(R.string.action_stop), stopPi)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(
                CHANNEL_ID,
                getString(R.string.notif_channel_name),
                NotificationManager.IMPORTANCE_LOW,
            ).apply {
                description = getString(R.string.notif_channel_desc)
            }
            nm.createNotificationChannel(channel)
        }
    }

    companion object {
        const val EXTRA_GPX_PATH = "gpx_path"
        const val ACTION_STOP = "com.gpssimulator.app.STOP"
        const val ACTION_TOGGLE_PAUSE = "com.gpssimulator.app.TOGGLE_PAUSE"
        const val ACTION_TOGGLE_DIRECTION = "com.gpssimulator.app.TOGGLE_DIRECTION"
        private const val CHANNEL_ID = "mock_location"
        private const val NOTIF_ID = 4711

        fun start(context: Context, gpxPath: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                putExtra(EXTRA_GPX_PATH, gpxPath)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            send(context, ACTION_STOP)
        }

        fun togglePause(context: Context) {
            send(context, ACTION_TOGGLE_PAUSE)
        }

        fun toggleDirection(context: Context) {
            send(context, ACTION_TOGGLE_DIRECTION)
        }

        private fun send(context: Context, action: String) {
            val intent = Intent(context, MockLocationService::class.java).apply {
                this.action = action
            }
            context.startService(intent)
        }
    }
}

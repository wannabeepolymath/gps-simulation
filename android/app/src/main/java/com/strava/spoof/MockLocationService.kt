package com.strava.spoof

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
    private lateinit var locationManager: LocationManager

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

        emitJob?.cancel()
        emitJob = scope.launch { runEmission(File(path).name, points) }
        return START_STICKY
    }

    private suspend fun runEmission(fileName: String, points: List<TrackPoint>) {
        val t0 = points.first().time
        val totalSeconds = (points.last().time.epochSecond - t0.epochSecond).coerceAtLeast(1)
        val startWall = SystemClock.elapsedRealtime()

        var prevLat = points.first().lat
        var prevLon = points.first().lon
        var prevWall = startWall

        while (true) {
            val now = SystemClock.elapsedRealtime()
            val elapsedMs = now - startWall
            val elapsedSec = elapsedMs / 1000

            val sample = interpolate(points, elapsedMs / 1000.0)
            val holding = sample.holding
            val pt = sample.point

            val dtSec = ((now - prevWall) / 1000.0).coerceAtLeast(0.001)
            val distM = Geo.haversineMeters(prevLat, prevLon, pt.lat, pt.lon)
            val speed = if (holding) 0f else (distM / dtSec).toFloat()
            prevLat = pt.lat
            prevLon = pt.lon
            prevWall = now

            val loc = buildLocation(pt, speed)
            try {
                locationManager.setTestProviderLocation(LocationManager.GPS_PROVIDER, loc)
            } catch (e: SecurityException) {
                ServiceState.set(RunState.Failed("Lost mock location permission: ${e.message}"))
                stopAll()
                return
            }

            ServiceState.set(
                RunState.Running(
                    fileName = fileName,
                    elapsedSeconds = elapsedSec.coerceAtMost(totalSeconds),
                    totalSeconds = totalSeconds,
                    holdingLastPoint = holding,
                )
            )

            delay(1000L)
        }
    }

    private data class Sample(val point: TrackPoint, val holding: Boolean)

    private fun interpolate(points: List<TrackPoint>, elapsedSec: Double): Sample {
        val t0 = points.first().time.epochSecond.toDouble()
        val tTarget = t0 + elapsedSec
        val last = points.last()
        if (tTarget >= last.time.epochSecond) {
            return Sample(last, holding = true)
        }
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
        return Sample(
            TrackPoint(
                lat = a.lat + (b.lat - a.lat) * f,
                lon = a.lon + (b.lon - a.lon) * f,
                ele = if (a.ele != null && b.ele != null) a.ele + (b.ele - a.ele) * f else (a.ele ?: b.ele),
                time = a.time,
            ),
            holding = false,
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
        return try {
            try { locationManager.removeTestProvider(LocationManager.GPS_PROVIDER) } catch (_: Exception) {}
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val props = ProviderProperties.Builder()
                    .setHasNetworkRequirement(false)
                    .setHasCellRequirement(false)
                    .setHasSatelliteRequirement(true)
                    .setHasMonetaryCost(false)
                    .setHasAltitudeSupport(true)
                    .setHasSpeedSupport(true)
                    .setHasBearingSupport(true)
                    .setPowerUsage(ProviderProperties.POWER_USAGE_LOW)
                    .setAccuracy(ProviderProperties.ACCURACY_FINE)
                    .build()
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(LocationManager.GPS_PROVIDER, props)
            } else {
                @Suppress("DEPRECATION")
                locationManager.addTestProvider(
                    LocationManager.GPS_PROVIDER,
                    /* requiresNetwork = */ false,
                    /* requiresSatellite = */ true,
                    /* requiresCell = */ false,
                    /* hasMonetaryCost = */ false,
                    /* supportsAltitude = */ true,
                    /* supportsSpeed = */ true,
                    /* supportsBearing = */ true,
                    /* powerRequirement = */ 1,
                    /* accuracy = */ 1,
                )
            }
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, true)
            providerAdded = true
            true
        } catch (e: SecurityException) {
            false
        } catch (e: IllegalArgumentException) {
            false
        }
    }

    private fun tearDownProvider() {
        if (!providerAdded) return
        try {
            locationManager.setTestProviderEnabled(LocationManager.GPS_PROVIDER, false)
            locationManager.removeTestProvider(LocationManager.GPS_PROVIDER)
        } catch (_: Exception) {}
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
        const val ACTION_STOP = "com.strava.spoof.STOP"
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
            val intent = Intent(context, MockLocationService::class.java).apply {
                action = ACTION_STOP
            }
            context.startService(intent)
        }
    }
}

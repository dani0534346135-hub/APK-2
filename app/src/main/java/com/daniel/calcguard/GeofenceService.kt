package com.daniel.calcguard

import android.annotation.SuppressLint
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Build
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat

class GeofenceService : Service(), LocationListener {

    private lateinit var locationManager: LocationManager
    private var targetLat: Double = 0.0
    private var targetLng: Double = 0.0
    private var radiusMeters: Float = 200f
    private var alertSentForCurrentExit: Boolean = false

    companion object {
        const val CHANNEL_ID = "CalcGuardGeofenceChannel"
        const val NOTIFICATION_ID = 2026
        const val EXTRA_LAT = "extra_lat"
        const val EXTRA_LNG = "extra_lng"
        const val EXTRA_RADIUS = "extra_radius"
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val notification = buildForegroundNotification("גדר גיאוגרפית פעילה במכשיר")
        startForeground(NOTIFICATION_ID, notification)
        locationManager = getSystemService(Context.LOCATION_SERVICE) as LocationManager
    }

    @SuppressLint("MissingPermission")
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val prefs = getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
        targetLat = intent?.getDoubleExtra(EXTRA_LAT, prefs.getFloat("geofence_lat", 0f).toDouble())
            ?: prefs.getFloat("geofence_lat", 0f).toDouble()
        targetLng = intent?.getDoubleExtra(EXTRA_LNG, prefs.getFloat("geofence_lng", 0f).toDouble())
            ?: prefs.getFloat("geofence_lng", 0f).toDouble()
        radiusMeters = intent?.getFloatExtra(EXTRA_RADIUS, prefs.getFloat("geofence_radius", 200f))
            ?: prefs.getFloat("geofence_radius", 200f)

        alertSentForCurrentExit = false

        try {
            locationManager.removeUpdates(this)
            if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.GPS_PROVIDER,
                    15000L,
                    10f,
                    this,
                    Looper.getMainLooper()
                )
            }
            if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)) {
                locationManager.requestLocationUpdates(
                    LocationManager.NETWORK_PROVIDER,
                    15000L,
                    10f,
                    this,
                    Looper.getMainLooper()
                )
            }
        } catch (_: Exception) { }

        return START_STICKY
    }

    override fun onLocationChanged(location: Location) {
        if (targetLat == 0.0 && targetLng == 0.0) return

        val results = FloatArray(1)
        Location.distanceBetween(
            location.latitude,
            location.longitude,
            targetLat,
            targetLng,
            results
        )
        val distance = results[0]

        if (distance > radiusMeters) {
            if (!alertSentForCurrentExit) {
                alertSentForCurrentExit = true
                val prefs = getSharedPreferences("CalcGuardPrefs", Context.MODE_PRIVATE)
                val alertTarget = prefs.getString("allowed_contact", "")?.trim() ?: ""
                if (alertTarget.isNotEmpty() && alertTarget.any { it.isDigit() }) {
                    val mapLink = "https://maps.google.com/?q=${location.latitude},${location.longitude}"
                    CommandExecutor.sendSmsDirect(
                        this,
                        alertTarget,
                        "🚨 התראת גדר גיאוגרפית: המכשיר יצא מהאזור המוגדר (${distance.toInt()} מ').\n$mapLink"
                    )
                }
            }
        } else {
            alertSentForCurrentExit = false
        }
    }

    override fun onDestroy() {
        try {
            locationManager.removeUpdates(this)
        } catch (_: Exception) { }
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "מעקב גדר גיאוגרפית",
                NotificationManager.IMPORTANCE_LOW
            )
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(channel)
        }
    }

    private fun buildForegroundNotification(contentText: String): Notification {
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("מחשבון – שירות מיקום")
            .setContentText(contentText)
            .setSmallIcon(R.drawable.ic_calculator)
            .setOngoing(true)
            .build()
    }
}

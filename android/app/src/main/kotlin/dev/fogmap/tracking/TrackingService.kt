package dev.fogmap.tracking

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.os.IBinder
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.fogmap.FogStore
import dev.fogmap.FogmapApp
import dev.fogmap.MainActivity
import dev.fogmap.R
import dev.fogmap.core.track.Fix
import dev.fogmap.core.track.FixFilter

/**
 * Пишет трек, пока приложение свёрнуто. Foreground-сервис здесь не украшение: без него система
 * перестаёт отдавать позицию в фоне, а с ним `ACCESS_BACKGROUND_LOCATION` не нужен — сервис
 * стартует из видимого приложения и наследует его доступ к геолокации.
 */
class TrackingService : Service() {

    private lateinit var store: FogStore
    private lateinit var client: FusedLocationProviderClient
    private val filter = FixFilter()

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            // Колбэк приходит на main-потоке — см. requestLocationUpdates ниже. Это то, что нужно
            // FogStore: маска живёт на main.
            for (location in result.locations) {
                val fix = Fix(
                    lat = location.latitude,
                    lon = location.longitude,
                    accuracyM = location.accuracy,
                    timeMs = location.time,
                )
                val accepted = filter.accept(fix)
                for (point in accepted) {
                    store.reveal(point.lat, point.lon)
                }
                if (accepted.isNotEmpty()) Tracking.setLastFix(fix)
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        store = (application as FogmapApp).fogStore
        store.load()
        client = LocationServices.getFusedLocationProviderClient(this)
        Tracking.setRunning(true)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForeground(NOTIFICATION_ID, notification())

        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, INTERVAL_MS)
            .setMinUpdateDistanceMeters(MIN_DISTANCE_M)
            .setWaitForAccurateLocation(false)
            .build()
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Разрешение отозвали, пока сервис поднимался.
            stopSelf()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        client.removeLocationUpdates(callback)
        Tracking.setRunning(false)
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun notification(): Notification {
        val manager = getSystemService(NotificationManager::class.java)
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            manager.createNotificationChannel(
                NotificationChannel(
                    CHANNEL_ID,
                    getString(R.string.tracking_channel),
                    NotificationManager.IMPORTANCE_LOW,
                ),
            )
        }

        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.tracking_title))
            .setContentText(getString(R.string.tracking_text))
            .setSmallIcon(R.drawable.ic_tracking)
            .setContentIntent(open)
            .setOngoing(true)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "tracking"
        const val NOTIFICATION_ID = 1

        /** Раз в 5 секунд: чаще — лишний расход, реже — дыры в треке при быстрой ходьбе. */
        const val INTERVAL_MS = 5_000L

        /** Меньше этого смещения обновление не нужно: маска всё равно не изменится. */
        const val MIN_DISTANCE_M = 10f
    }
}

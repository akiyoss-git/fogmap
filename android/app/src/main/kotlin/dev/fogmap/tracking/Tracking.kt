package dev.fogmap.tracking

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Looper
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import dev.fogmap.core.track.Fix
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Управление трекингом и его состояние для UI.
 *
 * Флаг выставляет сам сервис в onCreate/onDestroy, а не эти методы: иначе после перезапуска
 * сервиса системой (START_STICKY) UI показывал бы «выключено» при работающем трекинге.
 */
object Tracking {

    private val _running = MutableStateFlow(false)
    val running: StateFlow<Boolean> = _running.asStateFlow()

    /** Последняя принятая позиция — точка старта для маршрута. Null, если трекинг ещё не работал. */
    private val _lastFix = MutableStateFlow<Fix?>(null)
    val lastFix: StateFlow<Fix?> = _lastFix.asStateFlow()

    fun start(context: Context) {
        context.startForegroundService(Intent(context, TrackingService::class.java))
    }

    fun stop(context: Context) {
        context.stopService(Intent(context, TrackingService::class.java))
    }

    internal fun setRunning(running: Boolean) {
        _running.value = running
    }

    internal fun setLastFix(fix: Fix) {
        _lastFix.value = fix
    }

    /**
     * Где человек находится — чтобы показать карту там, а не в захардкоженной точке.
     *
     * Сначала пробуем закэшированную позицию, она приходит мгновенно. Если её нет — запрашиваем
     * свежую: `lastLocation` пуст, пока систему никто недавно не спрашивал, а это ровно случай
     * первого запуска на свежем устройстве или после перезагрузки.
     *
     * Позиции может не быть вовсе (геолокация выключена) — тогда обратный вызов не сработает и
     * карта останется на запасной точке.
     */
    fun currentLocation(context: Context, onResult: (Double, Double) -> Unit) {
        val granted = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED
        if (!granted) return

        val client = LocationServices.getFusedLocationProviderClient(context)
        try {
            client.lastLocation.addOnSuccessListener { cached ->
                if (cached != null) {
                    onResult(cached.latitude, cached.longitude)
                } else {
                    requestSingleFix(client, onResult)
                }
            }
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и запросом.
        }
    }

    /**
     * Подписка ровно на одно обновление вместо `getCurrentLocation`.
     *
     * Одноразовый запрос на эмуляторе не возвращает ничего: позиция, заданная через `adb emu geo
     * fix`, доходит только до активной подписки. Раз подписка работает и там, и на устройстве —
     * пользуемся ей, заодно поведение становится проверяемым.
     */
    private fun requestSingleFix(
        client: FusedLocationProviderClient,
        onResult: (Double, Double) -> Unit,
    ) {
        val request = LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY, 1000)
            .setMaxUpdates(1)
            .setDurationMillis(SINGLE_FIX_TIMEOUT_MS)
            .build()

        val callback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                client.removeLocationUpdates(this)
                result.lastLocation?.let { onResult(it.latitude, it.longitude) }
            }
        }
        try {
            client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        } catch (e: SecurityException) {
            // Разрешение отозвали между проверкой и запросом.
        }
    }

    /** Дольше держать приёмник ради разовой центровки карты незачем. */
    private const val SINGLE_FIX_TIMEOUT_MS = 15_000L
}

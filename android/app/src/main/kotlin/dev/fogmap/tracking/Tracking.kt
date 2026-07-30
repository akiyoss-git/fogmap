package dev.fogmap.tracking

import android.content.Context
import android.content.Intent
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
}

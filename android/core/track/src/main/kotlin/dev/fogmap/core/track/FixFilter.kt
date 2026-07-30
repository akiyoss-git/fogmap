package dev.fogmap.core.track

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Замер позиции. Без `android.location.Location`, чтобы модуль оставался чистым JVM. */
data class Fix(val lat: Double, val lon: Double, val accuracyM: Float, val timeMs: Long)

/** Точность хуже этой — фикс мусорный. */
const val MAX_ACCURACY_M = 50f

/** Быстрее этого человек не ходит: 200 км/ч. Всё, что выше, — прыжок GPS или подделка. */
const val MAX_SPEED_MPS = 55.6

/**
 * Шаг достройки пути между фиксами. Должен быть заметно меньше радиуса вскрытия, иначе при быстрой
 * ходьбе в маске останутся непройденные промежутки.
 */
const val STEP_M = 30.0

/** Предохранитель на случай длинной паузы между фиксами: не строить бесконечный список точек. */
private const val MAX_STEPS = 200

/**
 * Отбраковывает мусорные фиксы и достраивает путь между соседними.
 *
 * Не потокобезопасен: хранит предыдущий фикс, вызывать с одного потока.
 */
class FixFilter(
    private val maxAccuracyM: Float = MAX_ACCURACY_M,
    private val maxSpeedMps: Double = MAX_SPEED_MPS,
    private val stepM: Double = STEP_M,
) {

    private var previous: Fix? = null

    /**
     * Точки, которые надо вскрыть: промежуточные плюс сам фикс. Пустой список — фикс отброшен.
     *
     * Прыжок быстрее [maxSpeedMps] не вскрывает ничего, но сбрасывает точку отсчёта: сегмент не
     * засчитан, а трекинг продолжается с нового места. Так глюк GPS не пробивает дыру в случайной
     * точке карты, но и не блокирует трекинг навсегда.
     */
    fun accept(fix: Fix): List<Fix> {
        if (fix.accuracyM > maxAccuracyM) return emptyList()

        val last = previous
        previous = fix
        if (last == null) return listOf(fix)

        val seconds = (fix.timeMs - last.timeMs) / 1000.0
        if (seconds <= 0.0) {
            // Фиксы вне порядка или с одинаковым временем: скорость посчитать нельзя.
            previous = last
            return emptyList()
        }

        val distance = distanceM(last, fix)
        if (distance / seconds > maxSpeedMps) return emptyList()

        val steps = min(floor(distance / stepM).toInt(), MAX_STEPS)
        if (steps == 0) return listOf(fix)

        return buildList(steps + 1) {
            for (i in 1..steps) {
                val t = i.toDouble() / (steps + 1)
                add(
                    Fix(
                        lat = last.lat + (fix.lat - last.lat) * t,
                        lon = last.lon + (fix.lon - last.lon) * t,
                        accuracyM = fix.accuracyM,
                        timeMs = last.timeMs + ((fix.timeMs - last.timeMs) * t).toLong(),
                    ),
                )
            }
            add(fix)
        }
    }

    /** Сбрасывает точку отсчёта — например, при возобновлении трекинга после паузы. */
    fun reset() {
        previous = null
    }
}

private const val EARTH_RADIUS_M = 6_371_008.8

/** Гаверсинус. На расстояниях трекинга плоская формула тоже сойдётся, но эта не врёт у полюсов. */
fun distanceM(from: Fix, to: Fix): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.lon - from.lon)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

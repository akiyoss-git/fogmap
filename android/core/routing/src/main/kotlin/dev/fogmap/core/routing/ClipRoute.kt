package dev.fogmap.core.routing

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Точка маршрута, WGS84. Порядок (lat, lon) — как во всём проекте. */
data class RoutePoint(val lat: Double, val lon: Double)

/** Открытый участок рисуется линией, скрытый — редкими точками-крошками. */
enum class SegmentKind { REVEALED, HIDDEN }

data class RouteSegment(val kind: SegmentKind, val points: List<RoutePoint>)

/**
 * Шаг ресемплинга. Соразмерен ячейке маски (~5–10 м): крупнее — теряются границы тумана,
 * мельче — ничего не добавляет, только считается дольше.
 */
const val RESAMPLE_STEP_M = 15.0

/**
 * Сегмент короче наследует тип соседей.
 *
 * Без этого маршрут вдоль границы тумана превращается в мерцающий пунктир: маска не идеально
 * ровная, и линия многократно пересекает край.
 */
const val MIN_SEGMENT_M = 80.0

/** Состояние маски. Интерфейс, чтобы модуль не зависел от `core:fog` и тестировался синтетикой. */
fun interface RevealedLookup {
    fun isRevealed(lat: Double, lon: Double): Boolean
}

/**
 * Режет геометрию маршрута на открытые и скрытые участки.
 *
 * Возвращает либо один сегмент на весь маршрут, либо сегменты длиной не меньше [minSegmentM].
 */
fun clipRoute(
    geometry: List<RoutePoint>,
    revealed: RevealedLookup,
    stepM: Double = RESAMPLE_STEP_M,
    minSegmentM: Double = MIN_SEGMENT_M,
): List<RouteSegment> {
    if (geometry.size < 2) return emptyList()

    val points = resample(geometry, stepM)
    val runs = toRuns(points) { revealed.isRevealed(it.lat, it.lon) }
    smooth(runs, points, minSegmentM)

    return runs.map { run ->
        RouteSegment(
            kind = if (run.revealed) SegmentKind.REVEALED else SegmentKind.HIDDEN,
            points = points.subList(run.from, run.to + 1).toList(),
        )
    }
}

/** Точки маршрута через равные промежутки. Концы сохраняются как есть. */
internal fun resample(geometry: List<RoutePoint>, stepM: Double): List<RoutePoint> {
    val out = ArrayList<RoutePoint>()
    out.add(geometry.first())

    var sinceLast = 0.0
    for (i in 0 until geometry.size - 1) {
        val from = geometry[i]
        val to = geometry[i + 1]
        val length = distanceM(from, to)
        if (length <= 0.0) continue

        var passed = 0.0
        while (sinceLast + (length - passed) >= stepM) {
            passed += stepM - sinceLast
            out.add(interpolate(from, to, passed / length))
            sinceLast = 0.0
        }
        sinceLast += length - passed
    }

    if (out.last() != geometry.last()) out.add(geometry.last())
    return out
}

private class Run(var revealed: Boolean, val from: Int, var to: Int)

private fun toRuns(points: List<RoutePoint>, revealed: (RoutePoint) -> Boolean): MutableList<Run> {
    val runs = ArrayList<Run>()
    for (i in points.indices) {
        val flag = revealed(points[i])
        val last = runs.lastOrNull()
        if (last != null && last.revealed == flag) last.to = i else runs.add(Run(flag, i, i))
    }
    return runs
}

/**
 * Убирает слишком короткие участки: самый короткий переворачивается и сливается с соседями,
 * пока все не станут длиннее порога или не останется один.
 *
 * Каждый переворот уменьшает число участков минимум на один — цикл конечен.
 */
private fun smooth(runs: MutableList<Run>, points: List<RoutePoint>, minSegmentM: Double) {
    while (runs.size > 1) {
        var shortest = 0
        var shortestLength = Double.MAX_VALUE
        for (i in runs.indices) {
            val length = lengthOf(points, runs[i])
            if (length < shortestLength) {
                shortestLength = length
                shortest = i
            }
        }
        if (shortestLength >= minSegmentM) return

        runs[shortest].revealed = !runs[shortest].revealed
        coalesce(runs)
    }
}

private fun coalesce(runs: MutableList<Run>) {
    var i = 0
    while (i < runs.size - 1) {
        if (runs[i].revealed == runs[i + 1].revealed) {
            runs[i].to = runs[i + 1].to
            runs.removeAt(i + 1)
        } else {
            i++
        }
    }
}

private fun lengthOf(points: List<RoutePoint>, run: Run): Double {
    var sum = 0.0
    for (i in run.from until run.to) sum += distanceM(points[i], points[i + 1])
    return sum
}

private fun interpolate(from: RoutePoint, to: RoutePoint, t: Double) = RoutePoint(
    lat = from.lat + (to.lat - from.lat) * t,
    lon = from.lon + (to.lon - from.lon) * t,
)

private const val EARTH_RADIUS_M = 6_371_008.8

/**
 * Гаверсинус. Такой же есть в `core:track` — общий модуль ради шести строк стандартной формулы
 * дороже, чем эта копия.
 */
fun distanceM(from: RoutePoint, to: RoutePoint): Double {
    val lat1 = Math.toRadians(from.lat)
    val lat2 = Math.toRadians(to.lat)
    val dLat = lat2 - lat1
    val dLon = Math.toRadians(to.lon - from.lon)
    val a = sin(dLat / 2) * sin(dLat / 2) +
        cos(lat1) * cos(lat2) * sin(dLon / 2) * sin(dLon / 2)
    return 2 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

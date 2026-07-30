package dev.fogmap.core.fog

import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh

/** Зум тайлов маски. */
const val TILE_ZOOM = 14

/** Ячеек по стороне тайла. */
const val TILE_CELLS = 256

/** log2(TILE_CELLS) — сдвиг между координатой ячейки и координатой тайла. */
const val CELL_BITS = 8

/**
 * Ячейка маски совпадает с пикселем растрового тайла z14, поэтому мир — это сетка 2^22 ячеек
 * по каждой оси.
 */
const val WORLD_CELLS = 1 shl (TILE_ZOOM + CELL_BITS)

/** Длина экватора, м (WGS84). */
const val EARTH_CIRCUMFERENCE_M = 40_075_016.686

/** Предел Web Mercator: за ним проекция уходит в бесконечность. */
const val MAX_LATITUDE = 85.05112878

/** Радиус вскрытия вокруг точки по умолчанию, м. */
const val DEFAULT_REVEAL_RADIUS_M = 60.0

/**
 * Пересчёт между WGS84 и сеткой ячеек Web Mercator.
 *
 * Все методы работают в координатах ячеек (0 until [WORLD_CELLS]); координата тайла получается
 * сдвигом на [CELL_BITS].
 */
object TileMath {

    fun cellX(lon: Double): Int {
        val x = (lon.coerceIn(-180.0, 180.0) + 180.0) / 360.0 * WORLD_CELLS
        return x.toInt().coerceIn(0, WORLD_CELLS - 1)
    }

    fun cellY(lat: Double): Int {
        val s = sin(Math.toRadians(lat.coerceIn(-MAX_LATITUDE, MAX_LATITUDE)))
        val y = (0.5 - ln((1 + s) / (1 - s)) / (4 * PI)) * WORLD_CELLS
        return y.toInt().coerceIn(0, WORLD_CELLS - 1)
    }

    /** Долгота левой границы ячейки [cellX]. */
    fun lonOfCellX(cellX: Int): Double = cellX.toDouble() / WORLD_CELLS * 360.0 - 180.0

    /** Широта верхней границы ячейки [cellY]. */
    fun latOfCellY(cellY: Int): Double {
        val n = PI - 2.0 * PI * cellY / WORLD_CELLS
        return Math.toDegrees(atan(sinh(n)))
    }

    /**
     * Размер ячейки на земле. В Mercator он зависит от широты: ~9.55 м на экваторе и ~5.4 м на
     * широте Москвы. Это нормально — площадь считается с поправкой, см. [cellAreaM2].
     */
    fun metersPerCell(lat: Double): Double =
        EARTH_CIRCUMFERENCE_M * cos(Math.toRadians(lat)) / WORLD_CELLS

    /**
     * Площадь одной ячейки тайла [tileY]. Внутри тайла z14 широта меняется меньше чем на 0.03°,
     * поэтому берём широту центра тайла и считаем все его ячейки одинаковыми.
     */
    fun cellAreaM2(tileY: Int): Double {
        val lat = latOfCellY((tileY shl CELL_BITS) + TILE_CELLS / 2)
        val m = metersPerCell(lat)
        return m * m
    }
}

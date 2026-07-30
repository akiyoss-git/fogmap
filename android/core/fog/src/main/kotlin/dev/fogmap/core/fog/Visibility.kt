package dev.fogmap.core.fog

/** Радиус обзора, м. Дальше этого не вскрываем, даже если ничего не мешает. */
const val SIGHT_RADIUS_M = 100.0

/**
 * Ближний радиус, который вскрывается безусловно, без учёта стен.
 *
 * Точность GPS в городе 10–30 м, у высоких домов хуже из-за переотражений. Тени, построенные от
 * точки с промахом 20 м, врут, и без этой поблажки человек регулярно не вскрывал бы улицу, на
 * которой стоит.
 */
const val NEAR_RADIUS_M = 30.0

/**
 * Сколько ячеек здания вскрывать заливкой. Предохранитель: в OSM встречаются «здания» длиной в
 * километры, и заливать их целиком по одному увиденному углу не надо.
 */
const val MAX_BUILDING_CELLS = 20_000

/**
 * Растр препятствий: та же сетка, что у тумана, бит означает «здесь стена».
 *
 * Хранилище повторяет [FogMask] — там та же карта тайлов. Общий базовый класс ради двадцати строк
 * заводить не стал: у масок разное всё остальное, а связывать их наследованием ради экономии
 * плумбинга дороже, чем эта копия.
 */
class ObstacleMask {

    private val tiles = HashMap<TileId, FogTile>()

    val isEmpty: Boolean get() = tiles.isEmpty()

    fun block(cellX: Int, cellY: Int) {
        val id = TileId(cellX shr CELL_BITS, cellY shr CELL_BITS)
        val local = cellX and (TILE_CELLS - 1)
        tiles.getOrPut(id) { FogTile() }.fillRow(cellY and (TILE_CELLS - 1), local, local)
    }

    fun isBlocked(cellX: Int, cellY: Int): Boolean {
        if (cellX < 0 || cellX >= WORLD_CELLS || cellY < 0 || cellY >= WORLD_CELLS) return false
        val tile = tiles[TileId(cellX shr CELL_BITS, cellY shr CELL_BITS)] ?: return false
        return tile.isRevealed(cellX and (TILE_CELLS - 1), cellY and (TILE_CELLS - 1))
    }

    fun put(tileX: Int, tileY: Int, tile: FogTile) {
        tiles[TileId(tileX, tileY)] = tile
    }
}

/**
 * Рекурсивный shadowcasting по восьми октантам.
 *
 * Классический алгоритм: идём по строкам от наблюдателя, держим сектор видимости в виде пары
 * наклонов, встреченная стена рассекает сектор надвое и рекурсия продолжает уцелевшую часть.
 * Стена, которую видно, тоже считается видимой — иначе дома выглядели бы как дыры.
 */
object Visibility {

    private val TRANSFORMS = arrayOf(
        intArrayOf(1, 0, 0, 1),
        intArrayOf(0, 1, 1, 0),
        intArrayOf(0, -1, 1, 0),
        intArrayOf(-1, 0, 0, 1),
        intArrayOf(-1, 0, 0, -1),
        intArrayOf(0, -1, -1, 0),
        intArrayOf(0, 1, -1, 0),
        intArrayOf(1, 0, 0, -1),
    )

    fun visibleFrom(
        centerX: Int,
        centerY: Int,
        radiusCells: Int,
        blocked: (Int, Int) -> Boolean,
        visit: (Int, Int) -> Unit,
    ) {
        visit(centerX, centerY)
        for (transform in TRANSFORMS) {
            cast(
                centerX = centerX,
                centerY = centerY,
                row = 1,
                startSlope = 1.0,
                endSlope = 0.0,
                xx = transform[0],
                xy = transform[1],
                yx = transform[2],
                yy = transform[3],
                radiusCells = radiusCells,
                blocked = blocked,
                visit = visit,
            )
        }
    }

    private fun cast(
        centerX: Int,
        centerY: Int,
        row: Int,
        startSlope: Double,
        endSlope: Double,
        xx: Int,
        xy: Int,
        yx: Int,
        yy: Int,
        radiusCells: Int,
        blocked: (Int, Int) -> Boolean,
        visit: (Int, Int) -> Unit,
    ) {
        if (startSlope < endSlope) return

        var start = startSlope
        var nextStart = startSlope
        var inShadow = false
        var distance = row

        while (distance <= radiusCells && !inShadow) {
            val dy = -distance
            for (dx in -distance..0) {
                val x = centerX + dx * xx + dy * xy
                val y = centerY + dx * yx + dy * yy
                val leftSlope = (dx - 0.5) / (dy + 0.5)
                val rightSlope = (dx + 0.5) / (dy - 0.5)

                if (start < rightSlope) continue
                if (endSlope > leftSlope) break

                if (dx * dx + dy * dy <= radiusCells * radiusCells) visit(x, y)

                if (inShadow) {
                    if (blocked(x, y)) {
                        nextStart = rightSlope
                    } else {
                        inShadow = false
                        start = nextStart
                    }
                } else if (blocked(x, y) && distance < radiusCells) {
                    // Стена рассекает сектор: продолжаем видимую часть слева от неё рекурсией,
                    // а справа сужаем текущий сектор.
                    inShadow = true
                    cast(
                        centerX, centerY, distance + 1, start, leftSlope,
                        xx, xy, yx, yy, radiusCells, blocked, visit,
                    )
                    nextStart = rightSlope
                }
            }
            distance++
        }
    }
}

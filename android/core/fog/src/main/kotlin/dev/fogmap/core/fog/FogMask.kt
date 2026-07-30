package dev.fogmap.core.fog

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.sqrt

/** Сериализованный тайл: то, что уходит в БД и на сервер. */
class TileSnapshot(val id: TileId, val mask: ByteArray, val revealedCells: Int)

/**
 * Открытая часть карты — разреженный набор тайлов [FogTile].
 *
 * Не потокобезопасен: мутируется только с одного потока (в приложении — с main). Запись в БД идёт
 * через [snapshot], чтобы фоновый поток не читал массив, который в этот момент меняется.
 */
class FogMask {

    private val tiles = HashMap<TileId, FogTile>()

    fun tile(tileX: Int, tileY: Int): FogTile? = tiles[TileId(tileX, tileY)]

    fun tileIds(): Set<TileId> = tiles.keys.toSet()

    /**
     * Стирает маску целиком.
     *
     * Инвариант «открытая ячейка не закрывается» относится к слиянию и к ходу игры — здесь речь о
     * другом: пользователь потребовал удалить свои данные, и это единственный законный повод.
     */
    fun wipe() {
        tiles.clear()
    }

    /**
     * Вливает тайл в маску. Именно слияние, а не замена: загрузка из БД может прийти позже, чем
     * первое вскрытие от трекера, и не должна его затирать.
     */
    fun merge(tileX: Int, tileY: Int, tile: FogTile) {
        val id = TileId(tileX, tileY)
        val existing = tiles[id]
        if (existing == null) tiles[id] = tile else existing.merge(tile)
    }

    fun isRevealed(lat: Double, lon: Double): Boolean =
        isCellRevealed(TileMath.cellX(lon), TileMath.cellY(lat))

    /**
     * Состояние ячейки по глобальным координатам сетки. За пределами мира — закрыто.
     *
     * Нужен рендеру: размытие границы тумана читает ячейки соседних тайлов, иначе размытие
     * упирается в край тайла и на стыках проступает сетка.
     */
    fun isCellRevealed(cellX: Int, cellY: Int): Boolean {
        if (cellX < 0 || cellX >= WORLD_CELLS || cellY < 0 || cellY >= WORLD_CELLS) return false
        val tile = tile(cellX shr CELL_BITS, cellY shr CELL_BITS) ?: return false
        return tile.isRevealed(cellX and (TILE_CELLS - 1), cellY and (TILE_CELLS - 1))
    }

    /**
     * Открывает круг радиуса [radiusM] вокруг точки. Возвращает изменившиеся тайлы — их и только их
     * нужно перерисовать и сохранить.
     *
     * Круг строится в координатах ячеек: Mercator конформен, поэтому на масштабе десятков метров
     * круг на земле остаётся кругом в сетке.
     */
    fun reveal(lat: Double, lon: Double, radiusM: Double = DEFAULT_REVEAL_RADIUS_M): Set<TileId> {
        val changed = HashSet<TileId>()
        fillCircle(
            centerX = TileMath.cellX(lon),
            centerY = TileMath.cellY(lat),
            radiusCells = radiusM / TileMath.metersPerCell(lat),
            changed = changed,
        )
        return changed
    }

    /**
     * Открывает то, что видно с точки: до [sightRadiusM], но не сквозь стены.
     *
     * Ближние [nearRadiusM] открываются безусловно — см. [NEAR_RADIUS_M] о точности GPS.
     * Увиденное здание вскрывается целиком заливкой по связной области стен: иначе получаются дома,
     * обрезанные по линии взгляда, и это выглядит как баг, а не как замысел.
     *
     * Если данных о препятствиях нет, поведение вырождается в обычный круг — приложение обязано
     * работать в регионе, для которого растр зданий ещё не скачан.
     */
    fun revealVisible(
        lat: Double,
        lon: Double,
        obstacles: ObstacleMask,
        sightRadiusM: Double = SIGHT_RADIUS_M,
        nearRadiusM: Double = NEAR_RADIUS_M,
    ): Set<TileId> {
        if (obstacles.isEmpty) return reveal(lat, lon, sightRadiusM)

        val centerX = TileMath.cellX(lon)
        val centerY = TileMath.cellY(lat)
        val metersPerCell = TileMath.metersPerCell(lat)
        val changed = HashSet<TileId>()

        fillCircle(centerX, centerY, nearRadiusM / metersPerCell, changed)

        val seenWalls = ArrayList<Long>()
        Visibility.visibleFrom(
            centerX = centerX,
            centerY = centerY,
            radiusCells = (sightRadiusM / metersPerCell).toInt(),
            blocked = { x, y -> obstacles.isBlocked(x, y) },
            visit = { x, y ->
                revealCell(x, y, changed)
                if (obstacles.isBlocked(x, y)) seenWalls.add(key(x, y))
            },
        )

        val visited = HashSet<Long>()
        for (wall in seenWalls) {
            revealBuilding(unpackX(wall), unpackY(wall), obstacles, visited, changed)
        }
        return changed
    }

    /** Открытая площадь, м². Считается по тайлам с поправкой на широту. */
    fun areaM2(): Double = tiles.entries.sumOf { (id, tile) ->
        tile.revealedCells * TileMath.cellAreaM2(id.y)
    }

    /**
     * Копия указанных тайлов для записи. Сериализация делается на потоке-владельце — так фоновая
     * запись в БД не может увидеть полуобновлённую маску.
     */
    fun snapshot(ids: Set<TileId>): List<TileSnapshot> = ids.mapNotNull { id ->
        tiles[id]?.let { TileSnapshot(id, it.toBytes(), it.revealedCells) }
    }

    private fun fillCircle(
        centerX: Int,
        centerY: Int,
        radiusCells: Double,
        changed: MutableSet<TileId>,
    ) {
        val maxDy = ceil(radiusCells).toInt()
        for (dy in -maxDy..maxDy) {
            val cellY = centerY + dy
            if (cellY < 0 || cellY >= WORLD_CELLS) continue

            val halfSpan = sqrt(max(0.0, radiusCells * radiusCells - dy.toDouble() * dy))
            val fromX = ceil(centerX - halfSpan).toInt().coerceIn(0, WORLD_CELLS - 1)
            val toX = floor(centerX + halfSpan).toInt().coerceIn(0, WORLD_CELLS - 1)
            if (toX < fromX) continue
            fillSpan(cellY, fromX, toX, changed)
        }
    }

    /** Строка может пересекать границу тайлов — режем её по тайлам. */
    private fun fillSpan(cellY: Int, fromX: Int, toX: Int, changed: MutableSet<TileId>) {
        var x = fromX
        while (x <= toX) {
            val tileX = x shr CELL_BITS
            val tileY = cellY shr CELL_BITS
            val rowEnd = minOf(toX, (tileX shl CELL_BITS) + TILE_CELLS - 1)
            val id = TileId(tileX, tileY)
            val added = tiles.getOrPut(id) { FogTile() }.fillRow(
                cellY = cellY and (TILE_CELLS - 1),
                fromX = x and (TILE_CELLS - 1),
                toX = rowEnd and (TILE_CELLS - 1),
            )
            if (added > 0) changed += id
            x = rowEnd + 1
        }
    }

    private fun revealCell(cellX: Int, cellY: Int, changed: MutableSet<TileId>) {
        if (cellX < 0 || cellX >= WORLD_CELLS || cellY < 0 || cellY >= WORLD_CELLS) return
        fillSpan(cellY, cellX, cellX, changed)
    }

    /**
     * Заливка связной области стен от увиденной ячейки.
     *
     * Связность по четырём соседям, а не по восьми: иначе дома, соприкасающиеся углами, слипаются
     * в один и вскрываются вместе.
     */
    private fun revealBuilding(
        startX: Int,
        startY: Int,
        obstacles: ObstacleMask,
        visited: MutableSet<Long>,
        changed: MutableSet<TileId>,
    ) {
        if (!visited.add(key(startX, startY))) return

        val queue = ArrayDeque<Long>()
        queue.addLast(key(startX, startY))
        var count = 0

        while (queue.isNotEmpty() && count < MAX_BUILDING_CELLS) {
            val current = queue.removeFirst()
            val x = unpackX(current)
            val y = unpackY(current)
            revealCell(x, y, changed)
            count++

            for (i in 0 until 4) {
                val nx = x + NEIGHBOUR_DX[i]
                val ny = y + NEIGHBOUR_DY[i]
                if (obstacles.isBlocked(nx, ny) && visited.add(key(nx, ny))) {
                    queue.addLast(key(nx, ny))
                }
            }
        }
    }

    private companion object {
        val NEIGHBOUR_DX = intArrayOf(1, -1, 0, 0)
        val NEIGHBOUR_DY = intArrayOf(0, 0, 1, -1)

        fun key(x: Int, y: Int): Long = (x.toLong() shl 32) or (y.toLong() and 0xFFFF_FFFFL)
        fun unpackX(key: Long): Int = (key shr 32).toInt()
        fun unpackY(key: Long): Int = key.toInt()
    }
}

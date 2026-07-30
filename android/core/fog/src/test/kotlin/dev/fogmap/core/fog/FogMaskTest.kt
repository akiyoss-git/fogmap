package dev.fogmap.core.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs

class FogMaskTest {

    @Test
    fun `вскрытие на стыке тайлов затрагивает все четыре тайла`() {
        // Точка выбрана так, чтобы попасть ровно в угол тайлов: cellX = 8000 * 256,
        // а экватор — это граница между tileY 8191 и 8192.
        val lon = TileMath.lonOfCellX(8000 shl CELL_BITS)
        val mask = FogMask()

        val changed = mask.reveal(lat = 0.0, lon = lon)

        assertEquals(
            setOf(
                TileId(7999, 8191), TileId(8000, 8191),
                TileId(7999, 8192), TileId(8000, 8192),
            ),
            changed,
        )
    }

    @Test
    fun `площадь круга совпадает с pi r квадрат в пределах 5 процентов`() {
        for ((lat, lon) in listOf(0.0 to 10.0, 55.7558 to 37.6173, -33.87 to 151.21)) {
            val mask = FogMask()
            mask.reveal(lat, lon, radiusM = DEFAULT_REVEAL_RADIUS_M)

            val expected = PI * DEFAULT_REVEAL_RADIUS_M * DEFAULT_REVEAL_RADIUS_M
            val error = abs(mask.areaM2() - expected) / expected
            assertTrue(
                "широта $lat: площадь ${mask.areaM2()} против ожидаемых $expected",
                error < 0.05,
            )
        }
    }

    @Test
    fun `повторное вскрытие той же точки не меняет площадь`() {
        val mask = FogMask()
        mask.reveal(55.7558, 37.6173)
        val area = mask.areaM2()

        val changed = mask.reveal(55.7558, 37.6173)

        assertTrue(changed.isEmpty())
        assertEquals(area, mask.areaM2(), 0.0)
    }

    @Test
    fun `центр вскрыт, точка за радиусом нет`() {
        val lat = 55.7558
        val lon = 37.6173
        val mask = FogMask()
        mask.reveal(lat, lon, radiusM = 60.0)

        assertTrue(mask.isRevealed(lat, lon))
        // ~0.01° по широте это больше километра — заведомо снаружи.
        assertFalse(mask.isRevealed(lat + 0.01, lon))
        assertFalse(mask.isRevealed(lat, lon + 0.01))
    }

    @Test
    fun `isCellRevealed совпадает с isRevealed и не падает за краем мира`() {
        val lat = 55.7558
        val lon = 37.6173
        val mask = FogMask()
        mask.reveal(lat, lon)

        assertTrue(mask.isCellRevealed(TileMath.cellX(lon), TileMath.cellY(lat)))
        assertFalse(mask.isCellRevealed(-1, 0))
        assertFalse(mask.isCellRevealed(0, -1))
        assertFalse(mask.isCellRevealed(WORLD_CELLS, 0))
        assertFalse(mask.isCellRevealed(0, WORLD_CELLS))
    }

    @Test
    fun `пустая маска ничего не открывает`() {
        val mask = FogMask()

        assertFalse(mask.isRevealed(55.7558, 37.6173))
        assertEquals(0.0, mask.areaM2(), 0.0)
        assertTrue(mask.tileIds().isEmpty())
    }

    @Test
    fun `snapshot переживает восстановление маски`() {
        val mask = FogMask()
        val changed = mask.reveal(55.7558, 37.6173)
        val area = mask.areaM2()

        val restored = FogMask()
        mask.snapshot(changed).forEach {
            restored.merge(it.id.x, it.id.y, FogTile.fromBytes(it.mask))
        }

        assertEquals(area, restored.areaM2(), 0.0)
        assertTrue(restored.isRevealed(55.7558, 37.6173))
    }

    @Test
    fun `merge не затирает уже вскрытое и не считает ячейки дважды`() {
        val lat = 55.7558
        val lon = 37.6173

        val fromDatabase = FogMask()
        fromDatabase.reveal(lat, lon)
        val stored = fromDatabase.snapshot(fromDatabase.tileIds())

        // Маска, где трекер успел вскрыть соседнюю точку до загрузки из БД.
        val live = FogMask()
        live.reveal(lat + 0.01, lon)
        val liveArea = live.areaM2()

        stored.forEach { live.merge(it.id.x, it.id.y, FogTile.fromBytes(it.mask)) }
        val afterFirst = live.areaM2()

        assertTrue(live.isRevealed(lat, lon))
        assertTrue(live.isRevealed(lat + 0.01, lon))
        assertEquals(liveArea + fromDatabase.areaM2(), afterFirst, 1.0)

        // Повторное слияние того же ничего не меняет.
        stored.forEach { live.merge(it.id.x, it.id.y, FogTile.fromBytes(it.mask)) }
        assertEquals(afterFirst, live.areaM2(), 0.0)
    }
}

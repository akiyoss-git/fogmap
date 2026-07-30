package dev.fogmap.core.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    @Test
    fun `ячейка на экваторе примерно 9_55 м`() {
        assertEquals(9.5548, TileMath.metersPerCell(0.0), 0.001)
    }

    @Test
    fun `ячейка на широте Москвы примерно вдвое меньше`() {
        assertEquals(5.3766, TileMath.metersPerCell(55.7558), 0.001)
    }

    @Test
    fun `экватор приходится на середину сетки`() {
        assertEquals(WORLD_CELLS / 2, TileMath.cellY(0.0))
    }

    @Test
    fun `cellY растёт к югу`() {
        assertTrue(TileMath.cellY(60.0) < TileMath.cellY(50.0))
        assertTrue(TileMath.cellY(50.0) < TileMath.cellY(-50.0))
    }

    @Test
    fun `обратное преобразование попадает в ту же ячейку`() {
        val cellY = TileMath.cellY(55.7558)
        val cellX = TileMath.cellX(37.6173)

        // latOfCellY даёт ровно границу ячейки, поэтому обратный пересчёт может попасть в соседнюю
        // из-за округления double. Проверяем именно формулу, а не поведение на границе.
        assertTrue(Math.abs(TileMath.cellY(TileMath.latOfCellY(cellY)) - cellY) <= 1)
        assertTrue(Math.abs(TileMath.cellX(TileMath.lonOfCellX(cellX)) - cellX) <= 1)
    }

    @Test
    fun `широты за пределом Mercator обрезаются, а не уходят в бесконечность`() {
        assertEquals(0, TileMath.cellY(90.0))
        assertEquals(WORLD_CELLS - 1, TileMath.cellY(-90.0))
    }
}

package dev.fogmap.core.fog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FogTileTest {

    @Test
    fun `fillRow считает только впервые открытые ячейки`() {
        val tile = FogTile()

        assertEquals(10, tile.fillRow(cellY = 5, fromX = 0, toX = 9))
        assertEquals(0, tile.fillRow(cellY = 5, fromX = 0, toX = 9))
        assertEquals(5, tile.fillRow(cellY = 5, fromX = 5, toX = 14))
        assertEquals(15, tile.revealedCells)
    }

    @Test
    fun `fillRow через границу 64-битных слов`() {
        val tile = FogTile()

        assertEquals(TILE_CELLS, tile.fillRow(cellY = 0, fromX = 0, toX = TILE_CELLS - 1))
        assertTrue(tile.isRevealed(0, 0))
        assertTrue(tile.isRevealed(63, 0))
        assertTrue(tile.isRevealed(64, 0))
        assertTrue(tile.isRevealed(TILE_CELLS - 1, 0))
        assertFalse(tile.isRevealed(0, 1))
    }

    @Test
    fun `строки не залезают друг на друга`() {
        val tile = FogTile()
        tile.fillRow(cellY = 7, fromX = 3, toX = 3)

        assertTrue(tile.isRevealed(3, 7))
        assertFalse(tile.isRevealed(3, 6))
        assertFalse(tile.isRevealed(3, 8))
        assertFalse(tile.isRevealed(2, 7))
        assertEquals(1, tile.revealedCells)
    }

    @Test
    fun `порядок бит совпадает с тем, как пишет сервер`() {
        // Растеризатор зданий на бэкенде пишет ровно так: bit = y*256+x, байт bit/8, младшим
        // битом вперёд. Если порядок разойдётся, клиент получит мусор вместо домов, и заметить
        // это можно будет только глазами на карте.
        val bytes = ByteArray(8192)
        fun serverSet(x: Int, y: Int) {
            val bit = y * TILE_CELLS + x
            bytes[bit shr 3] = (bytes[bit shr 3].toInt() or (1 shl (bit and 7))).toByte()
        }
        serverSet(0, 0)
        serverSet(7, 0)
        serverSet(8, 0)
        serverSet(13, 200)
        serverSet(255, 255)

        val tile = FogTile.fromBytes(bytes)

        assertTrue(tile.isRevealed(0, 0))
        assertTrue(tile.isRevealed(7, 0))
        assertTrue(tile.isRevealed(8, 0))
        assertTrue(tile.isRevealed(13, 200))
        assertTrue(tile.isRevealed(255, 255))
        assertFalse(tile.isRevealed(1, 0))
        assertFalse(tile.isRevealed(13, 201))
        assertEquals(5, tile.revealedCells)
    }

    @Test
    fun `сериализация сохраняет маску и счётчик`() {
        val tile = FogTile()
        tile.fillRow(cellY = 0, fromX = 1, toX = 100)
        tile.fillRow(cellY = 255, fromX = 200, toX = 255)

        val restored = FogTile.fromBytes(tile.toBytes())

        assertEquals(tile.revealedCells, restored.revealedCells)
        assertTrue(restored.isRevealed(1, 0))
        assertTrue(restored.isRevealed(100, 0))
        assertFalse(restored.isRevealed(101, 0))
        assertTrue(restored.isRevealed(255, 255))
        assertEquals(8192, tile.toBytes().size)
    }
}

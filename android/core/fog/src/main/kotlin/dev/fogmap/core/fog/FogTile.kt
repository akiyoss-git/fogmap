package dev.fogmap.core.fog

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Координаты тайла маски на зуме [TILE_ZOOM]. */
data class TileId(val x: Int, val y: Int)

/**
 * Битовая маска одного тайла: [TILE_CELLS] × [TILE_CELLS] бит, 8 КБ.
 *
 * Маска grow-only — открытая ячейка не закрывается никогда. Поэтому здесь нет ни `clear`, ни
 * инвалидации: только [fillRow] и слияние через OR на стороне сервера.
 */
class FogTile private constructor(private val words: LongArray) {

    constructor() : this(LongArray(WORDS))

    /** Сколько ячеек открыто. Обновляется инкрементально, полный popcount не нужен. */
    var revealedCells: Int = words.sumOf { java.lang.Long.bitCount(it) }
        private set

    fun isRevealed(cellX: Int, cellY: Int): Boolean {
        val word = words[cellY * WORDS_PER_ROW + (cellX ushr 6)]
        return (word ushr (cellX and 63)) and 1L != 0L
    }

    /**
     * Закрашивает ячейки [fromX]..[toX] включительно в строке [cellY].
     * Возвращает число ячеек, открытых именно этим вызовом (повторное закрашивание даёт 0).
     */
    fun fillRow(cellY: Int, fromX: Int, toX: Int): Int {
        val base = cellY * WORDS_PER_ROW
        val firstWord = fromX ushr 6
        val lastWord = toX ushr 6
        var added = 0
        for (w in firstWord..lastWord) {
            val lo = if (w == firstWord) fromX and 63 else 0
            val hi = if (w == lastWord) toX and 63 else 63
            val i = base + w
            val before = words[i]
            val after = before or spanMask(lo, hi)
            if (after != before) {
                added += java.lang.Long.bitCount(after) - java.lang.Long.bitCount(before)
                words[i] = after
            }
        }
        revealedCells += added
        return added
    }

    /**
     * Побитовое ИЛИ с другим тайлом. Возвращает число ячеек, открытых именно этим слиянием.
     *
     * Маска grow-only, поэтому слияние идемпотентно и коммутативно: порядок не важен, повтор
     * ничего не меняет. На этом же построен merge между устройствами на сервере.
     */
    fun merge(other: FogTile): Int {
        var added = 0
        for (i in words.indices) {
            val before = words[i]
            val after = before or other.words[i]
            if (after != before) {
                added += java.lang.Long.bitCount(after) - java.lang.Long.bitCount(before)
                words[i] = after
            }
        }
        revealedCells += added
        return added
    }

    fun toBytes(): ByteArray {
        val bytes = ByteArray(WORDS * Long.SIZE_BYTES)
        ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().put(words)
        return bytes
    }

    companion object {
        const val WORDS_PER_ROW = TILE_CELLS / Long.SIZE_BITS
        const val WORDS = TILE_CELLS * WORDS_PER_ROW

        fun fromBytes(bytes: ByteArray): FogTile {
            require(bytes.size == WORDS * Long.SIZE_BYTES) {
                "ожидалось ${WORDS * Long.SIZE_BYTES} байт маски, получено ${bytes.size}"
            }
            val words = LongArray(WORDS)
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asLongBuffer().get(words)
            return FogTile(words)
        }

        /** Long с единицами в битах [lo]..[hi] включительно. */
        private fun spanMask(lo: Int, hi: Int): Long {
            val count = hi - lo + 1
            val ones = if (count == Long.SIZE_BITS) -1L else (1L shl count) - 1L
            return ones shl lo
        }
    }
}

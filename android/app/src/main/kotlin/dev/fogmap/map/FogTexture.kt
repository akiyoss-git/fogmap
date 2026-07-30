package dev.fogmap.map

import android.graphics.Bitmap
import dev.fogmap.core.fog.CELL_BITS
import dev.fogmap.core.fog.FogMask
import dev.fogmap.core.fog.FogTile
import dev.fogmap.core.fog.TILE_CELLS
import kotlin.math.floor

/**
 * Превращает битовую маску в то, что читается как туман: мягкая граница, сбитая шумом в клочья,
 * плюс облачная текстура для самого тумана.
 *
 * Всё считается по глобальным координатам ячеек, поэтому шум не привязан к тайлу и на стыках
 * тайлов нет швов.
 */
internal object FogTexture {

    /**
     * Цвет тумана: непрозрачный, светлый, с облачной модуляцией между этими двумя.
     * Разброс намеренно небольшой — текстура тайлится, и при высоком контрасте глаз начинает
     * читать повторяющуюся плитку вместо облаков.
     */
    const val FOG_LIGHT = 0xFFEDF1F4.toInt()
    const val FOG_DARK = 0xFFCED6DD.toInt()

    /** Радиус размытия границы, ячеек. При ячейке ~5 м это перо примерно в 30 м. */
    private const val BLUR_CELLS = 3

    /**
     * Насколько шум смещает границу: 0 — ровная окружность, 0.55 — рваные клочья.
     * Должно быть строго меньше 2 × [EDGE_LOW], иначе перестанет работать ранний выход
     * для ячеек, которые заведомо целиком в тумане или целиком вскрыты.
     */
    private const val WISP = 0.55f

    private const val EDGE_LOW = 0.35f
    private const val EDGE_HIGH = 0.65f

    /** Масштаб первой октавы шума, ячеек. */
    private const val NOISE_SCALE = 9f

    /**
     * Маска дырок тайла: непрозрачные пиксели вычитаются из тумана режимом DST_OUT,
     * промежуточная альфа даёт мягкий край.
     */
    fun holeMask(mask: FogMask, tileX: Int, tileY: Int): Bitmap {
        // Поля вдвое шире радиуса: у внешней их полосы суммы размытия неполные, и это не должно
        // дотягиваться до центральной области, которая идёт в битмап.
        val pad = 2 * BLUR_CELLS
        val side = TILE_CELLS + 2 * pad
        val originX = (tileX shl CELL_BITS) - pad
        val originY = (tileY shl CELL_BITS) - pad

        val coverage = boxBlur(sampleCells(mask, tileX, tileY, originX, originY, side), side, BLUR_CELLS)

        val pixels = IntArray(TILE_CELLS * TILE_CELLS)
        var i = 0
        for (y in 0 until TILE_CELLS) {
            val globalY = (tileY shl CELL_BITS) + y
            for (x in 0 until TILE_CELLS) {
                val c = coverage[(y + pad) * side + (x + pad)]
                val alpha = when {
                    // Глубоко внутри и глубоко снаружи шум ничего не решает — не считаем его.
                    c <= 0f -> 0f
                    c >= 1f -> 1f
                    else -> {
                        val globalX = (tileX shl CELL_BITS) + x
                        val n = fbm(globalX / NOISE_SCALE, globalY / NOISE_SCALE)
                        smoothstep(EDGE_LOW, EDGE_HIGH, c + (n - 0.5f) * WISP)
                    }
                }
                pixels[i++] = ((alpha * 255f).toInt() and 0xFF) shl 24
            }
        }
        return Bitmap.createBitmap(pixels, TILE_CELLS, TILE_CELLS, Bitmap.Config.ARGB_8888)
    }

    /** Бесшовно тайлящийся кусок облаков для заливки тумана. */
    fun cloudTile(size: Int): Bitmap {
        val pixels = IntArray(size * size)
        var i = 0
        for (y in 0 until size) {
            val fy = y.toFloat() / size
            for (x in 0 until size) {
                val fx = x.toFloat() / size
                val n = 0.5f * tileableNoise(fx * 3f, fy * 3f, 3) +
                    0.3f * tileableNoise(fx * 6f, fy * 6f, 6) +
                    0.2f * tileableNoise(fx * 13f, fy * 13f, 13)
                pixels[i++] = lerpColor(FOG_DARK, FOG_LIGHT, n)
            }
        }
        return Bitmap.createBitmap(pixels, size, size, Bitmap.Config.ARGB_8888)
    }

    /**
     * Читает ячейки своего тайла и полей из соседних. Соседи достаются один раз: обращаться к
     * маске на каждый пиксель — это 70 тысяч поисков по HashMap на тайл.
     */
    private fun sampleCells(
        mask: FogMask,
        tileX: Int,
        tileY: Int,
        originX: Int,
        originY: Int,
        side: Int,
    ): FloatArray {
        val neighbours = Array(3) { dy ->
            arrayOfNulls<FogTile>(3).also { row ->
                for (dx in 0..2) row[dx] = mask.tile(tileX + dx - 1, tileY + dy - 1)
            }
        }

        val cells = FloatArray(side * side)
        for (y in 0 until side) {
            val globalY = originY + y
            val tile = neighbours[(globalY shr CELL_BITS) - tileY + 1]
            val localY = globalY and (TILE_CELLS - 1)
            for (x in 0 until side) {
                val globalX = originX + x
                val t = tile[(globalX shr CELL_BITS) - tileX + 1] ?: continue
                if (t.isRevealed(globalX and (TILE_CELLS - 1), localY)) cells[y * side + x] = 1f
            }
        }
        return cells
    }

    /** Разделимое box-размытие бегущей суммой: два прохода вместо (2r+1)² выборок на пиксель. */
    private fun boxBlur(src: FloatArray, side: Int, radius: Int): FloatArray {
        val window = 2 * radius + 1
        val horizontal = FloatArray(side * side)
        for (y in 0 until side) {
            val row = y * side
            var sum = 0f
            for (i in 0..radius) sum += src[row + i]
            for (x in 0 until side) {
                horizontal[row + x] = sum / window
                val add = x + radius + 1
                val drop = x - radius
                if (add < side) sum += src[row + add]
                if (drop >= 0) sum -= src[row + drop]
            }
        }

        val out = FloatArray(side * side)
        for (x in 0 until side) {
            var sum = 0f
            for (i in 0..radius) sum += horizontal[i * side + x]
            for (y in 0 until side) {
                out[y * side + x] = sum / window
                val add = y + radius + 1
                val drop = y - radius
                if (add < side) sum += horizontal[add * side + x]
                if (drop >= 0) sum -= horizontal[drop * side + x]
            }
        }
        return out
    }

    private fun fbm(x: Float, y: Float): Float =
        0.65f * valueNoise(x, y) + 0.35f * valueNoise(x * 2.3f, y * 2.3f)

    private fun valueNoise(x: Float, y: Float): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val u = smooth(x - xi)
        val v = smooth(y - yi)
        return lerp(
            lerp(hash(xi, yi), hash(xi + 1, yi), u),
            lerp(hash(xi, yi + 1), hash(xi + 1, yi + 1), u),
            v,
        )
    }

    private fun tileableNoise(x: Float, y: Float, period: Int): Float {
        val xi = floor(x).toInt()
        val yi = floor(y).toInt()
        val u = smooth(x - xi)
        val v = smooth(y - yi)
        fun h(cx: Int, cy: Int) = hash(Math.floorMod(cx, period), Math.floorMod(cy, period))
        return lerp(
            lerp(h(xi, yi), h(xi + 1, yi), u),
            lerp(h(xi, yi + 1), h(xi + 1, yi + 1), u),
            v,
        )
    }

    private fun hash(x: Int, y: Int): Float {
        var h = x * 374761393 + y * 668265263
        h = (h xor (h shr 13)) * 1274126177
        h = h xor (h shr 16)
        return (h and 0xFFFF) / 65535f
    }

    private fun smooth(t: Float): Float = t * t * (3f - 2f * t)

    private fun lerp(a: Float, b: Float, t: Float): Float = a + (b - a) * t

    private fun smoothstep(from: Float, to: Float, value: Float): Float {
        val t = ((value - from) / (to - from)).coerceIn(0f, 1f)
        return smooth(t)
    }

    private fun lerpColor(from: Int, to: Int, t: Float): Int {
        val r = lerp(((from shr 16) and 0xFF).toFloat(), ((to shr 16) and 0xFF).toFloat(), t)
        val g = lerp(((from shr 8) and 0xFF).toFloat(), ((to shr 8) and 0xFF).toFloat(), t)
        val b = lerp((from and 0xFF).toFloat(), (to and 0xFF).toFloat(), t)
        return (0xFF shl 24) or (r.toInt() shl 16) or (g.toInt() shl 8) or b.toInt()
    }
}

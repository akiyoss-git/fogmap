package dev.fogmap.core.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FixFilterTest {

    private val lat = 55.7558
    private val lon = 37.6173

    private fun fix(
        lat: Double = this.lat,
        lon: Double = this.lon,
        accuracyM: Float = 10f,
        timeMs: Long = 0,
    ) = Fix(lat, lon, accuracyM, timeMs)

    /** Смещение по долготе на заданное расстояние в метрах. */
    private fun eastOf(meters: Double) = lon + meters / (111_320.0 * Math.cos(Math.toRadians(lat)))

    @Test
    fun `первый фикс принимается как есть`() {
        val result = FixFilter().accept(fix())

        assertEquals(1, result.size)
        assertEquals(lat, result[0].lat, 0.0)
    }

    @Test
    fun `фикс с плохой точностью отбрасывается`() {
        val filter = FixFilter()

        assertTrue(filter.accept(fix(accuracyM = MAX_ACCURACY_M + 1)).isEmpty())
        // И не становится точкой отсчёта: следующий нормальный фикс идёт как первый.
        assertEquals(1, filter.accept(fix(timeMs = 1000)).size)
    }

    @Test
    fun `короткий шаг не достраивается`() {
        val filter = FixFilter()
        filter.accept(fix(timeMs = 0))

        val result = filter.accept(fix(lon = eastOf(10.0), timeMs = 5000))

        assertEquals(1, result.size)
    }

    @Test
    fun `длинный отрезок достраивается с шагом не больше STEP_M`() {
        val filter = FixFilter()
        filter.accept(fix(timeMs = 0))

        // 300 м за 5 минут — быстрая ходьба, не телепорт.
        val result = filter.accept(fix(lon = eastOf(300.0), timeMs = 300_000))

        assertTrue("ожидалось несколько точек, получено ${result.size}", result.size >= 10)
        var previous = fix(timeMs = 0)
        for (point in result) {
            assertTrue(
                "разрыв ${distanceM(previous, point)} м больше шага $STEP_M",
                distanceM(previous, point) <= STEP_M + 1.0,
            )
            previous = point
        }
        // Последняя точка — сам фикс, без сдвига.
        assertEquals(eastOf(300.0), result.last().lon, 1e-9)
    }

    @Test
    fun `телепорт ничего не вскрывает, но сбрасывает точку отсчёта`() {
        val filter = FixFilter()
        filter.accept(fix(timeMs = 0))

        // 10 км за секунду.
        val jump = fix(lon = eastOf(10_000.0), timeMs = 1000)
        assertTrue(filter.accept(jump).isEmpty())

        // Дальше трекинг продолжается от нового места, а не от старого: короткий шаг от точки
        // прыжка не считается вторым телепортом.
        val next = Fix(lat, eastOf(10_010.0), 10f, 11_000)
        assertEquals(1, filter.accept(next).size)
    }

    @Test
    fun `фиксы с неправильным порядком времени отбрасываются`() {
        val filter = FixFilter()
        filter.accept(fix(timeMs = 10_000))

        assertTrue(filter.accept(fix(lon = eastOf(50.0), timeMs = 5000)).isEmpty())
        assertTrue(filter.accept(fix(lon = eastOf(50.0), timeMs = 10_000)).isEmpty())
    }

    @Test
    fun `reset разрывает связь с предыдущим фиксом`() {
        val filter = FixFilter()
        filter.accept(fix(timeMs = 0))
        filter.reset()

        // Без reset это был бы телепорт; после reset — обычный первый фикс.
        val result = filter.accept(fix(lon = eastOf(10_000.0), timeMs = 1000))

        assertEquals(1, result.size)
    }

    @Test
    fun `расстояние считается верно`() {
        // 1 градус широты это примерно 111 км.
        val d = distanceM(Fix(0.0, 0.0, 0f, 0), Fix(1.0, 0.0, 0f, 0))
        assertEquals(111_195.0, d, 100.0)
    }
}

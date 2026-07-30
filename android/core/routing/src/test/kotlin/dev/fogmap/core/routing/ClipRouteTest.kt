package dev.fogmap.core.routing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.sin

class ClipRouteTest {

    private val lat = 55.75
    private val startLon = 37.60

    /** Долгота, отстоящая от начала на заданное число метров к востоку. */
    private fun lonAt(meters: Double) =
        startLon + meters / (111_320.0 * Math.cos(Math.toRadians(lat)))

    /** Прямой маршрут на восток заданной длины, заданный только концами. */
    private fun straightRoute(lengthM: Double) =
        listOf(RoutePoint(lat, startLon), RoutePoint(lat, lonAt(lengthM)))

    private fun revealedBetween(fromM: Double, toM: Double) = RevealedLookup { _, lon ->
        lon >= lonAt(fromM) && lon <= lonAt(toM)
    }

    @Test
    fun `маршрут целиком открыт — один сегмент`() {
        val segments = clipRoute(straightRoute(1000.0), RevealedLookup { _, _ -> true })

        assertEquals(1, segments.size)
        assertEquals(SegmentKind.REVEALED, segments[0].kind)
    }

    @Test
    fun `маршрут целиком в тумане — один сегмент`() {
        val segments = clipRoute(straightRoute(1000.0), RevealedLookup { _, _ -> false })

        assertEquals(1, segments.size)
        assertEquals(SegmentKind.HIDDEN, segments[0].kind)
    }

    @Test
    fun `окно открытого посреди тумана даёт ровно три сегмента`() {
        val segments = clipRoute(straightRoute(1200.0), revealedBetween(400.0, 800.0))

        assertEquals(3, segments.size)
        assertEquals(SegmentKind.HIDDEN, segments[0].kind)
        assertEquals(SegmentKind.REVEALED, segments[1].kind)
        assertEquals(SegmentKind.HIDDEN, segments[2].kind)
    }

    @Test
    fun `сегменты идут подряд и покрывают весь маршрут`() {
        val segments = clipRoute(straightRoute(1200.0), revealedBetween(400.0, 800.0))

        val total = segments.sumOf { segment ->
            segment.points.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }
        }
        // Стыки между сегментами не покрыты — их ровно (сегментов - 1) штук по шагу ресемплинга.
        assertEquals(1200.0, total, RESAMPLE_STEP_M * segments.size)
    }

    @Test
    fun `короткое окно открытого поглощается туманом`() {
        // Окно 40 м — меньше MIN_SEGMENT_M, отдельным сегментом быть не должно.
        val segments = clipRoute(straightRoute(1000.0), revealedBetween(500.0, 540.0))

        assertEquals(1, segments.size)
        assertEquals(SegmentKind.HIDDEN, segments[0].kind)
    }

    @Test
    fun `маршрут вдоль рваной границы не рассыпается на мелкие сегменты`() {
        // Полосы открытого и закрытого шириной в десятки метров — худший случай для границы.
        val ragged = RevealedLookup { _, lon -> sin((lon - startLon) * 40_000.0) > 0 }

        val segments = clipRoute(straightRoute(2000.0), ragged)

        assertTrue("сегментов не должно быть ноль", segments.isNotEmpty())
        if (segments.size > 1) {
            for (segment in segments) {
                val length = segment.points.zipWithNext().sumOf { (a, b) -> distanceM(a, b) }
                assertTrue("сегмент $length м короче порога $MIN_SEGMENT_M", length >= MIN_SEGMENT_M)
            }
        }
    }

    @Test
    fun `число сегментов не зависит от шага ресемплинга`() {
        val revealed = revealedBetween(400.0, 900.0)
        val route = straightRoute(1500.0)

        val counts = listOf(10.0, 15.0, 20.0).map { step ->
            clipRoute(route, revealed, stepM = step).size
        }

        assertEquals("шаги дали разное число сегментов: $counts", 1, counts.distinct().size)
    }

    @Test
    fun `слишком короткая геометрия не даёт сегментов`() {
        assertTrue(clipRoute(emptyList(), RevealedLookup { _, _ -> true }).isEmpty())
        assertTrue(
            clipRoute(listOf(RoutePoint(lat, startLon)), RevealedLookup { _, _ -> true }).isEmpty(),
        )
    }

    @Test
    fun `ресемплинг ставит точки через заданный шаг и сохраняет концы`() {
        val points = resample(straightRoute(300.0), stepM = 50.0)

        assertEquals(RoutePoint(lat, startLon), points.first())
        assertEquals(lonAt(300.0), points.last().lon, 1e-9)
        for ((a, b) in points.zipWithNext()) {
            assertTrue("шаг ${distanceM(a, b)} м больше 50", distanceM(a, b) <= 50.0 + 0.5)
        }
    }
}

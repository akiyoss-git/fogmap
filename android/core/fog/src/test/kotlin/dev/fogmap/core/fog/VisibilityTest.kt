package dev.fogmap.core.fog

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Планы строятся синтетически, в координатах ячеек: так тесты не зависят ни от данных OSM, ни от
 * широты, и проверяют ровно алгоритм.
 */
class VisibilityTest {

    /** Точка на широте Москвы, где ячейка ~5.4 м. */
    private val lat = 55.7558
    private val lon = 37.6173

    private val centerX = TileMath.cellX(lon)
    private val centerY = TileMath.cellY(lat)

    private fun cellsPerMeter() = 1.0 / TileMath.metersPerCell(lat)

    /** Смещение точки на заданное число ячеек к востоку. */
    private fun lonOfCell(cellX: Int) = TileMath.lonOfCellX(cellX)

    private fun latOfCell(cellY: Int) = TileMath.latOfCellY(cellY)

    private fun wallRow(obstacles: ObstacleMask, cellY: Int, fromX: Int, toX: Int) {
        for (x in fromX..toX) obstacles.block(x, cellY)
    }

    private fun wallColumn(obstacles: ObstacleMask, cellX: Int, fromY: Int, toY: Int) {
        for (y in fromY..toY) obstacles.block(cellX, y)
    }

    @Test
    fun `стена отбрасывает тень`() {
        val obstacles = ObstacleMask()
        // Стена в 5 ячейках севернее наблюдателя, широкая.
        wallRow(obstacles, centerY - 5, centerX - 20, centerX + 20)

        val mask = FogMask()
        mask.revealVisible(lat, lon, obstacles, sightRadiusM = 100.0, nearRadiusM = 0.0)

        assertTrue("саму стену видно", mask.isCellRevealed(centerX, centerY - 5))
        assertFalse("сразу за стеной темно", mask.isCellRevealed(centerX, centerY - 6))
        assertFalse("дальше за стеной тоже", mask.isCellRevealed(centerX, centerY - 12))
        assertTrue("перед стеной видно", mask.isCellRevealed(centerX, centerY - 4))
    }

    @Test
    fun `угол дома не протекает по диагонали`() {
        val obstacles = ObstacleMask()
        // Угол: стена идёт на север и на восток от одной точки.
        val cornerX = centerX + 4
        val cornerY = centerY - 4
        wallColumn(obstacles, cornerX, cornerY - 12, cornerY)
        wallRow(obstacles, cornerY, cornerX, cornerX + 12)

        val mask = FogMask()
        mask.revealVisible(lat, lon, obstacles, sightRadiusM = 100.0, nearRadiusM = 0.0)

        // Внутренность за углом закрыта с двух сторон — туда взгляд не попадает.
        assertFalse(mask.isCellRevealed(cornerX + 6, cornerY - 6))
        assertFalse(mask.isCellRevealed(cornerX + 3, cornerY - 3))
    }

    @Test
    fun `из замкнутой комнаты видно её целиком и не видно наружу`() {
        val obstacles = ObstacleMask()
        val half = 6
        // Стены комнаты вокруг наблюдателя.
        wallRow(obstacles, centerY - half, centerX - half, centerX + half)
        wallRow(obstacles, centerY + half, centerX - half, centerX + half)
        wallColumn(obstacles, centerX - half, centerY - half, centerY + half)
        wallColumn(obstacles, centerX + half, centerY - half, centerY + half)

        val mask = FogMask()
        mask.revealVisible(lat, lon, obstacles, sightRadiusM = 200.0, nearRadiusM = 0.0)

        for (dy in -half + 1..half - 1) {
            for (dx in -half + 1..half - 1) {
                assertTrue(
                    "внутри комнаты всё видно, а ($dx,$dy) нет",
                    mask.isCellRevealed(centerX + dx, centerY + dy),
                )
            }
        }
        assertFalse("за стеной наружу не видно", mask.isCellRevealed(centerX, centerY - half - 3))
        assertFalse(mask.isCellRevealed(centerX + half + 3, centerY))
    }

    @Test
    fun `увиденное здание вскрывается целиком, а не по фасаду`() {
        val obstacles = ObstacleMask()
        // Сплошной прямоугольный дом севернее наблюдателя.
        val from = centerY - 20
        val to = centerY - 10
        for (y in from..to) wallRow(obstacles, y, centerX - 8, centerX + 8)

        val mask = FogMask()
        mask.revealVisible(lat, lon, obstacles, sightRadiusM = 150.0, nearRadiusM = 0.0)

        assertTrue("фасад виден", mask.isCellRevealed(centerX, to))
        assertTrue("дальняя стена дома тоже вскрыта", mask.isCellRevealed(centerX, from))
        assertTrue("дальний угол дома вскрыт", mask.isCellRevealed(centerX - 8, from))
        assertFalse("но не то, что за домом", mask.isCellRevealed(centerX, from - 4))
    }

    @Test
    fun `вскрытие вдоль улицы не залезает во дворы за домами`() {
        val obstacles = ObstacleMask()
        // Улица идёт на восток, по обе стороны сплошные ряды домов толщиной 3 ячейки.
        val streetHalfWidth = 3
        for (thickness in 0..2) {
            wallRow(obstacles, centerY - streetHalfWidth - thickness, centerX - 60, centerX + 60)
            wallRow(obstacles, centerY + streetHalfWidth + thickness, centerX - 60, centerX + 60)
        }

        val mask = FogMask()
        // Проходим по улице несколько шагов.
        for (step in -10..10 step 5) {
            mask.revealVisible(
                latOfCell(centerY),
                lonOfCell(centerX + step),
                obstacles,
                sightRadiusM = 100.0,
                nearRadiusM = 0.0,
            )
        }

        assertTrue("улица вскрыта", mask.isCellRevealed(centerX, centerY))
        assertTrue("фасады вскрыты", mask.isCellRevealed(centerX, centerY - streetHalfWidth))
        assertFalse(
            "двор за северным рядом закрыт",
            mask.isCellRevealed(centerX, centerY - streetHalfWidth - 6),
        )
        assertFalse(
            "двор за южным рядом закрыт",
            mask.isCellRevealed(centerX, centerY + streetHalfWidth + 6),
        )
    }

    @Test
    fun `ближний радиус вскрывается сквозь стены`() {
        val obstacles = ObstacleMask()
        wallRow(obstacles, centerY - 2, centerX - 20, centerX + 20)

        val mask = FogMask()
        // Ближние 30 м — это ~5.5 ячеек на этой широте.
        mask.revealVisible(lat, lon, obstacles, sightRadiusM = 100.0, nearRadiusM = NEAR_RADIUS_M)

        assertTrue(
            "за стеной, но в ближнем радиусе — вскрыто, иначе GPS-промах глушит улицу под ногами",
            mask.isCellRevealed(centerX, centerY - 4),
        )
    }

    @Test
    fun `без данных о зданиях вырождается в обычный круг`() {
        val withoutObstacles = FogMask()
        val changed = withoutObstacles.revealVisible(lat, lon, ObstacleMask(), sightRadiusM = 60.0)

        val plainCircle = FogMask()
        plainCircle.reveal(lat, lon, radiusM = 60.0)

        assertTrue(changed.isNotEmpty())
        org.junit.Assert.assertEquals(plainCircle.areaM2(), withoutObstacles.areaM2(), 0.0)
    }

    @Test
    fun `расчёт видимости укладывается в бюджет одного фикса`() {
        val obstacles = ObstacleMask()
        // Плотная городская сетка: дома через каждые 10 ячеек в обе стороны.
        for (i in -60..60 step 10) {
            wallRow(obstacles, centerY + i, centerX - 60, centerX + 60)
            wallColumn(obstacles, centerX + i, centerY - 60, centerY + 60)
        }

        val mask = FogMask()
        // Прогрев: первый вызов включает JIT.
        mask.revealVisible(lat, lon, obstacles)

        val start = System.nanoTime()
        repeat(10) { mask.revealVisible(lat, lon, obstacles) }
        val perCallMs = (System.nanoTime() - start) / 10 / 1_000_000.0

        assertTrue("расчёт занял $perCallMs мс на вызов", perCallMs < 50.0)
    }
}

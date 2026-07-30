package dev.fogmap.fog;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Контрольные значения здесь те же, что в Kotlin-тесте {@code TileMathTest} на клиенте.
 *
 * <p>Это единственная защита от расхождения двух реализаций одной формулы: сервер — источник
 * истины для площади, и если он начнёт считать иначе, лидерборд разойдётся с тем, что видит
 * пользователь у себя на экране.
 */
class TileMathTest {

    @Test
    @DisplayName("ячейка на экваторе примерно 9.55 м")
    void cellSizeAtEquator() {
        assertEquals(9.5548, TileMath.metersPerCell(0.0), 0.001);
    }

    @Test
    @DisplayName("ячейка на широте Москвы примерно вдвое меньше")
    void cellSizeAtMoscow() {
        assertEquals(5.3766, TileMath.metersPerCell(55.7558), 0.001);
    }

    @Test
    @DisplayName("маска тайла занимает 8192 байта")
    void maskSize() {
        assertEquals(8192, TileMath.MASK_BYTES);
    }

    @Test
    @DisplayName("широта убывает с ростом cellY")
    void latDecreasesSouthward() {
        assertTrue(TileMath.latOfCellY(1_000_000) > TileMath.latOfCellY(3_000_000));
    }

    @Test
    @DisplayName("площадь ячейки на экваторе больше, чем в средних широтах")
    void cellAreaShrinksTowardsPoles() {
        int equatorTile = (TileMath.WORLD_CELLS / 2) >> TileMath.CELL_BITS;
        int moscowTile = 5122;
        assertTrue(TileMath.cellAreaM2(equatorTile) > TileMath.cellAreaM2(moscowTile));
    }

    @Test
    @DisplayName("тайлы за пределами мира отбраковываются")
    void tileRange() {
        assertTrue(TileMath.isValidTile(0, 0));
        assertTrue(TileMath.isValidTile(TileMath.WORLD_TILES - 1, TileMath.WORLD_TILES - 1));
        assertFalse(TileMath.isValidTile(-1, 0));
        assertFalse(TileMath.isValidTile(0, TileMath.WORLD_TILES));
    }
}

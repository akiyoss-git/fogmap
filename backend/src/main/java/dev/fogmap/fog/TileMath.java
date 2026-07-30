package dev.fogmap.fog;

/**
 * Пересчёт координат сетки тумана. Зеркало Kotlin-класса {@code core:fog/TileMath} на клиенте.
 *
 * <p>Числа обязаны совпадать с клиентскими: сервер — источник истины для площади, и если формулы
 * разъедутся, лидерборд начнёт спорить с тем, что видит пользователь. Тест
 * {@code TileMathTest} проверяет те же контрольные значения, что и Kotlin-тест на клиенте.
 */
public final class TileMath {

    public static final int TILE_ZOOM = 14;
    public static final int TILE_CELLS = 256;
    public static final int CELL_BITS = 8;
    public static final int WORLD_CELLS = 1 << (TILE_ZOOM + CELL_BITS);
    public static final int WORLD_TILES = 1 << TILE_ZOOM;
    public static final int MASK_BYTES = TILE_CELLS * TILE_CELLS / 8;

    public static final double EARTH_CIRCUMFERENCE_M = 40_075_016.686;

    private TileMath() {
    }

    /** Широта верхней границы ячейки. */
    public static double latOfCellY(int cellY) {
        double n = Math.PI - 2.0 * Math.PI * cellY / WORLD_CELLS;
        return Math.toDegrees(Math.atan(Math.sinh(n)));
    }

    /** Размер ячейки на земле: в Mercator он зависит от широты. */
    public static double metersPerCell(double lat) {
        return EARTH_CIRCUMFERENCE_M * Math.cos(Math.toRadians(lat)) / WORLD_CELLS;
    }

    /** Площадь одной ячейки тайла — по широте его центра. */
    public static double cellAreaM2(int tileY) {
        double lat = latOfCellY((tileY << CELL_BITS) + TILE_CELLS / 2);
        double m = metersPerCell(lat);
        return m * m;
    }

    public static boolean isValidTile(int x, int y) {
        return x >= 0 && x < WORLD_TILES && y >= 0 && y < WORLD_TILES;
    }
}

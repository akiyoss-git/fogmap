package dev.fogmap.obstacle;

import com.carrotsearch.hppc.LongArrayList;
import com.carrotsearch.hppc.LongHashSet;
import com.carrotsearch.hppc.LongLongHashMap;
import com.graphhopper.reader.ReaderElement;
import com.graphhopper.reader.ReaderNode;
import com.graphhopper.reader.ReaderWay;
import com.graphhopper.reader.osm.OSMInputFile;
import dev.fogmap.fog.TileMath;
import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Превращает контуры зданий из OSM в тот же растр, что и туман: тайлы z14 по 256×256 бит,
 * бит означает «здесь стена».
 *
 * <p>Порядок бит совпадает с клиентским {@code FogTile}: глобальный бит {@code y*256+x} лежит в
 * байте {@code bit/8}, младшим битом вперёд. Разъедется — клиент увидит мусор вместо домов.
 */
class ObstacleRasterizer {

    private static final Logger log = LoggerFactory.getLogger(ObstacleRasterizer.class);

    private final Map<Long, byte[]> tiles = new HashMap<>();

    Map<Long, byte[]> rasterize(File osmFile) {
        long started = System.currentTimeMillis();

        List<long[]> buildings = new ArrayList<>();
        LongHashSet neededNodes = new LongHashSet();
        readBuildingWays(osmFile, buildings, neededNodes);
        log.info("Зданий в экстракте: {}, узлов к разбору: {}", buildings.size(), neededNodes.size());

        LongLongHashMap nodeCells = readNodeCells(osmFile, neededNodes);
        neededNodes.release();

        for (long[] way : buildings) {
            rasterizeWay(way, nodeCells);
        }

        log.info("Растр зданий готов: {} тайлов за {} мс",
                tiles.size(), System.currentTimeMillis() - started);
        return tiles;
    }

    /** Первый проход: какие way — здания и какие узлы им нужны. */
    private void readBuildingWays(File osmFile, List<long[]> buildings, LongHashSet neededNodes) {
        try (OSMInputFile input = new OSMInputFile(osmFile).setWorkerThreads(2).open()) {
            ReaderElement element;
            while ((element = input.getNext()) != null) {
                if (!(element instanceof ReaderWay way) || way.getTag("building") == null) {
                    continue;
                }
                LongArrayList nodes = way.getNodes();
                if (nodes.size() < 3) continue;

                long[] ids = new long[nodes.size()];
                for (int i = 0; i < nodes.size(); i++) {
                    ids[i] = nodes.get(i);
                    neededNodes.add(ids[i]);
                }
                buildings.add(ids);
            }
        } catch (Exception e) {
            throw new IllegalStateException("не удалось прочитать здания из " + osmFile, e);
        }
    }

    /**
     * Второй проход: координаты нужных узлов, сразу в ячейках сетки.
     *
     * <p>Хранить широту с долготой нет смысла — дальше они всё равно превращаются в ячейки, а так
     * пара int'ов помещается в один long и карта занимает вдвое меньше.
     */
    private LongLongHashMap readNodeCells(File osmFile, LongHashSet neededNodes) {
        LongLongHashMap cells = new LongLongHashMap(neededNodes.size());
        try (OSMInputFile input = new OSMInputFile(osmFile).setWorkerThreads(2).open()) {
            ReaderElement element;
            while ((element = input.getNext()) != null) {
                if (!(element instanceof ReaderNode node) || !neededNodes.contains(node.getId())) {
                    continue;
                }
                int cellX = cellX(node.getLon());
                int cellY = cellY(node.getLat());
                cells.put(node.getId(), ((long) cellX << 32) | (cellY & 0xFFFF_FFFFL));
            }
        } catch (Exception e) {
            throw new IllegalStateException("не удалось прочитать узлы из " + osmFile, e);
        }
        return cells;
    }

    private void rasterizeWay(long[] nodeIds, LongLongHashMap nodeCells) {
        int[] xs = new int[nodeIds.length];
        int[] ys = new int[nodeIds.length];
        int count = 0;
        for (long id : nodeIds) {
            long packed = nodeCells.getOrDefault(id, Long.MIN_VALUE);
            if (packed == Long.MIN_VALUE) continue;
            xs[count] = (int) (packed >> 32);
            ys[count] = (int) packed;
            count++;
        }
        if (count < 3) return;

        fillPolygon(xs, ys, count);
        // Контур отдельно: у тонких строений развёртка по строкам не даёт ни одного отрезка.
        for (int i = 0; i < count; i++) {
            int j = (i + 1) % count;
            drawLine(xs[i], ys[i], xs[j], ys[j]);
        }
    }

    /** Развёртка по строкам с чётно-нечётным правилом. */
    private void fillPolygon(int[] xs, int[] ys, int count) {
        int minY = Integer.MAX_VALUE;
        int maxY = Integer.MIN_VALUE;
        for (int i = 0; i < count; i++) {
            minY = Math.min(minY, ys[i]);
            maxY = Math.max(maxY, ys[i]);
        }
        // Здание размером с город — почти наверняка ошибка разметки, а не дом.
        if (maxY - minY > MAX_BUILDING_SPAN_CELLS) return;

        int[] crossings = new int[count];
        for (int y = minY; y <= maxY; y++) {
            int found = 0;
            for (int i = 0; i < count; i++) {
                int j = (i + 1) % count;
                int y1 = ys[i];
                int y2 = ys[j];
                if ((y1 <= y && y2 > y) || (y2 <= y && y1 > y)) {
                    crossings[found++] = xs[i] + (y - y1) * (xs[j] - xs[i]) / (y2 - y1);
                }
            }
            Arrays.sort(crossings, 0, found);
            for (int i = 0; i + 1 < found; i += 2) {
                for (int x = crossings[i]; x <= crossings[i + 1]; x++) {
                    set(x, y);
                }
            }
        }
    }

    private void drawLine(int x1, int y1, int x2, int y2) {
        int dx = Math.abs(x2 - x1);
        int dy = -Math.abs(y2 - y1);
        if (dx > MAX_BUILDING_SPAN_CELLS || -dy > MAX_BUILDING_SPAN_CELLS) return;

        int stepX = x1 < x2 ? 1 : -1;
        int stepY = y1 < y2 ? 1 : -1;
        int error = dx + dy;
        int x = x1;
        int y = y1;
        while (true) {
            set(x, y);
            if (x == x2 && y == y2) break;
            int doubled = 2 * error;
            if (doubled >= dy) {
                error += dy;
                x += stepX;
            }
            if (doubled <= dx) {
                error += dx;
                y += stepY;
            }
        }
    }

    private void set(int cellX, int cellY) {
        if (cellX < 0 || cellX >= TileMath.WORLD_CELLS || cellY < 0 || cellY >= TileMath.WORLD_CELLS) {
            return;
        }
        long key = ObstacleService.tileKey(cellX >> TileMath.CELL_BITS, cellY >> TileMath.CELL_BITS);
        byte[] mask = tiles.computeIfAbsent(key, k -> new byte[TileMath.MASK_BYTES]);
        int bit = (cellY & (TileMath.TILE_CELLS - 1)) * TileMath.TILE_CELLS
                + (cellX & (TileMath.TILE_CELLS - 1));
        mask[bit >> 3] |= (byte) (1 << (bit & 7));
    }

    private static int cellX(double lon) {
        double x = (Math.min(180.0, Math.max(-180.0, lon)) + 180.0) / 360.0 * TileMath.WORLD_CELLS;
        return (int) Math.min(TileMath.WORLD_CELLS - 1, Math.max(0, x));
    }

    private static int cellY(double lat) {
        double clamped = Math.min(85.05112878, Math.max(-85.05112878, lat));
        double s = Math.sin(Math.toRadians(clamped));
        double y = (0.5 - Math.log((1 + s) / (1 - s)) / (4 * Math.PI)) * TileMath.WORLD_CELLS;
        return (int) Math.min(TileMath.WORLD_CELLS - 1, Math.max(0, y));
    }

    /** Примерно 8 км в ячейках: длиннее «здания» в OSM — это разметка чего-то другого. */
    private static final int MAX_BUILDING_SPAN_CELLS = 1500;
}

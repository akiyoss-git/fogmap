package dev.fogmap.obstacle;

import dev.fogmap.fog.TileMath;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

/**
 * Растр зданий в памяти.
 *
 * <p>Для Москвы это порядка тысячи тайлов по 8 КБ — около десяти мегабайт, поэтому база не нужна.
 * Результат кладётся в файл рядом с графом маршрутов: разбор экстракта занимает минуты, а чтение
 * готового растра — доли секунды, и перезапуск сервера не должен стоить этих минут.
 */
@Service
public class ObstacleService {

    /** Больше за раз не отдаём: клиенту нужен район вокруг человека, а не полрегиона. */
    static final int MAX_TILES_PER_REQUEST = 64;

    private static final Logger log = LoggerFactory.getLogger(ObstacleService.class);

    private final Map<Long, byte[]> tiles;

    public ObstacleService(
            @Value("${fogmap.routing.osm-file:}") String osmFile,
            @Value("${fogmap.routing.graph-dir}") String graphDir) {
        this.tiles = osmFile.isBlank() ? Map.of() : loadOrBuild(osmFile, graphDir);
    }

    public boolean isAvailable() {
        return !tiles.isEmpty();
    }

    public List<ObstacleDtos.TileDto> tilesIn(int minX, int minY, int maxX, int maxY) {
        if (tiles.isEmpty()) {
            throw new ResponseStatusException(
                    HttpStatus.SERVICE_UNAVAILABLE, "obstacles are not configured on this server");
        }
        if (maxX < minX || maxY < minY) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "empty range");
        }
        long requested = (long) (maxX - minX + 1) * (maxY - minY + 1);
        if (requested > MAX_TILES_PER_REQUEST) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too many tiles");
        }

        List<ObstacleDtos.TileDto> result = new ArrayList<>();
        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                byte[] mask = tiles.get(tileKey(x, y));
                // Тайлы без единого здания не отдаём: пустой блоб ничего не сообщает.
                if (mask != null) {
                    result.add(new ObstacleDtos.TileDto(x, y, mask));
                }
            }
        }
        return result;
    }

    static long tileKey(int tileX, int tileY) {
        return ((long) tileX << 32) | (tileY & 0xFFFF_FFFFL);
    }

    private static Map<Long, byte[]> loadOrBuild(String osmFile, String graphDir) {
        Path cache = Path.of(graphDir, "obstacles.bin");
        if (Files.exists(cache)) {
            try {
                Map<Long, byte[]> loaded = read(cache);
                log.info("Растр зданий прочитан из кэша: {} тайлов", loaded.size());
                return loaded;
            } catch (IOException e) {
                log.warn("Кэш растра повреждён, строю заново: {}", e.getMessage());
            }
        }

        Map<Long, byte[]> built = new ObstacleRasterizer().rasterize(new File(osmFile));
        try {
            Files.createDirectories(cache.getParent());
            write(cache, built);
        } catch (IOException e) {
            // Не смогли сохранить — не повод падать, просто следующий старт будет долгим.
            log.warn("Не удалось сохранить кэш растра: {}", e.getMessage());
        }
        return built;
    }

    private static Map<Long, byte[]> read(Path path) throws IOException {
        try (DataInputStream in = new DataInputStream(
                new BufferedInputStream(Files.newInputStream(path), 1 << 16))) {
            int count = in.readInt();
            Map<Long, byte[]> tiles = new HashMap<>(count * 2);
            for (int i = 0; i < count; i++) {
                int x = in.readInt();
                int y = in.readInt();
                byte[] mask = new byte[TileMath.MASK_BYTES];
                in.readFully(mask);
                tiles.put(tileKey(x, y), mask);
            }
            return tiles;
        }
    }

    private static void write(Path path, Map<Long, byte[]> tiles) throws IOException {
        try (DataOutputStream out = new DataOutputStream(
                new BufferedOutputStream(Files.newOutputStream(path), 1 << 16))) {
            out.writeInt(tiles.size());
            for (Map.Entry<Long, byte[]> entry : tiles.entrySet()) {
                out.writeInt((int) (entry.getKey() >> 32));
                out.writeInt(entry.getKey().intValue());
                out.write(entry.getValue());
            }
        }
    }
}

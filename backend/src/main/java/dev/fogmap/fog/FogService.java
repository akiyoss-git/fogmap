package dev.fogmap.fog;

import dev.fogmap.fog.FogDtos.SyncRequest;
import dev.fogmap.fog.FogDtos.SyncResponse;
import dev.fogmap.fog.FogDtos.TileDownload;
import dev.fogmap.fog.FogDtos.TileUpload;
import dev.fogmap.social.AchievementService;
import java.time.Instant;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FogService {

    /** Предохранитель от заливки всей планеты одним запросом. */
    static final int MAX_TILES_PER_SYNC = 512;

    private final FogTileRepository tiles;
    private final UserStatsRepository stats;
    private final AchievementService achievements;

    public FogService(FogTileRepository tiles, UserStatsRepository stats, AchievementService achievements) {
        this.tiles = tiles;
        this.stats = stats;
        this.achievements = achievements;
    }

    @Transactional
    public SyncResponse sync(long userId, SyncRequest request) {
        if (request.tiles().size() > MAX_TILES_PER_SYNC) {
            throw new ResponseStatusException(HttpStatus.PAYLOAD_TOO_LARGE, "too many tiles");
        }

        Instant now = Instant.now();
        for (TileUpload upload : request.tiles()) {
            validate(upload);
            byte[] existing = tiles.findMask(userId, upload.x(), upload.y()).orElse(null);
            // Побитовое ИЛИ: маска grow-only, поэтому порядок и повторы значения не имеют.
            byte[] merged = existing == null ? upload.mask() : MaskOps.or(existing, upload.mask());
            tiles.upsert(userId, upload.x(), upload.y(), merged, MaskOps.popCount(merged), now);
        }

        long areaM2 = recomputeArea(userId);
        // Считаем от того, что вычислил сервер, а не от присланного клиентом.
        achievements.evaluate(userId, areaM2, tiles.findStats(userId).size());

        Instant since = request.since() == null ? Instant.EPOCH : Instant.ofEpochMilli(request.since());
        // Свои же тайлы вернутся обратно — так и надо: в них мог влиться вклад другого устройства,
        // а слияние на клиенте идемпотентно.
        List<TileDownload> back = tiles.findUpdatedSince(userId, since).stream()
                .map(row -> new TileDownload(row.x(), row.y(), row.mask(), row.updatedAt().toEpochMilli()))
                .toList();

        return new SyncResponse(areaM2, tiles.findStats(userId).size(), back, now.toEpochMilli());
    }

    /**
     * Площадь пересчитывается целиком из блобов, а не накапливается приращениями.
     *
     * <p>Так синк идемпотентен по построению: повтор того же тайла даёт ту же площадь. Цена —
     * проход по всем тайлам пользователя на каждый синк; если станет тесно, это первое место для
     * оптимизации.
     */
    private long recomputeArea(long userId) {
        double area = 0;
        for (FogTileRepository.TileStat stat : tiles.findStats(userId)) {
            area += stat.revealedCells() * TileMath.cellAreaM2(stat.y());
        }
        long rounded = Math.round(area);
        stats.upsert(userId, rounded, tiles.findStats(userId).size(), Instant.now());
        return rounded;
    }

    private static void validate(TileUpload upload) {
        if (!TileMath.isValidTile(upload.x(), upload.y())) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "tile out of range");
        }
        if (upload.mask() == null || upload.mask().length != TileMath.MASK_BYTES) {
            throw new ResponseStatusException(
                    HttpStatus.BAD_REQUEST, "mask must be " + TileMath.MASK_BYTES + " bytes");
        }
    }
}

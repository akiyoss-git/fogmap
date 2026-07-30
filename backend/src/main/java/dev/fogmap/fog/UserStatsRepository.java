package dev.fogmap.fog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserStatsRepository {

    public record StatsRow(long areaM2, int tilesCount) {
    }

    /** Момент прошлой синхронизации; если её не было — момент создания аккаунта. */
    public Instant lastChangeOrSignup(long userId) {
        return jdbc.sql("""
                        select coalesce(s.updated_at, u.created_at)
                        from users u left join user_stats s on s.user_id = u.id
                        where u.id = :userId
                        """)
                .param("userId", userId)
                .query(java.sql.Timestamp.class)
                .optional()
                .map(java.sql.Timestamp::toInstant)
                .orElse(Instant.EPOCH);
    }

    private final JdbcClient jdbc;

    public UserStatsRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void upsert(long userId, long areaM2, int tilesCount, Instant updatedAt) {
        jdbc.sql("""
                        insert into user_stats (user_id, area_m2, tiles_count, updated_at)
                        values (:userId, :areaM2, :tilesCount, :updatedAt)
                        on conflict (user_id) do update
                        set area_m2 = excluded.area_m2,
                            tiles_count = excluded.tiles_count,
                            updated_at = excluded.updated_at
                        """)
                .param("userId", userId)
                .param("areaM2", areaM2)
                .param("tilesCount", tilesCount)
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public Optional<StatsRow> find(long userId) {
        return jdbc.sql("select area_m2, tiles_count from user_stats where user_id = :userId")
                .param("userId", userId)
                .query((rs, rowNum) -> new StatsRow(rs.getLong("area_m2"), rs.getInt("tiles_count")))
                .optional();
    }
}

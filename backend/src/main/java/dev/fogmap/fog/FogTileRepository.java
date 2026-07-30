package dev.fogmap.fog;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Время параметров переводится в {@link java.time.OffsetDateTime}: драйвер Postgres не умеет
 * выводить SQL-тип для {@link Instant} и падает на «Can't infer the SQL type».
 */
@Repository
public class FogTileRepository {

    public record TileRow(int x, int y, byte[] mask, Instant updatedAt) {
    }

    /** Только то, что нужно для площади: широта тайла и число открытых ячеек. */
    public record TileStat(int y, int revealedCells) {
    }

    private final JdbcClient jdbc;

    public FogTileRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<byte[]> findMask(long userId, int x, int y) {
        return jdbc.sql("select mask from fog_tiles where user_id = :userId and x = :x and y = :y")
                .param("userId", userId)
                .param("x", x)
                .param("y", y)
                .query(byte[].class)
                .optional();
    }

    public void upsert(long userId, int x, int y, byte[] mask, int revealedCells, Instant updatedAt) {
        jdbc.sql("""
                        insert into fog_tiles (user_id, x, y, mask, revealed_cells, updated_at)
                        values (:userId, :x, :y, :mask, :revealedCells, :updatedAt)
                        on conflict (user_id, x, y) do update
                        set mask = excluded.mask,
                            revealed_cells = excluded.revealed_cells,
                            updated_at = excluded.updated_at
                        """)
                .param("userId", userId)
                .param("x", x)
                .param("y", y)
                .param("mask", mask)
                .param("revealedCells", revealedCells)
                .param("updatedAt", updatedAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    public List<TileRow> findUpdatedSince(long userId, Instant since) {
        return jdbc.sql("""
                        select x, y, mask, updated_at from fog_tiles
                        where user_id = :userId and updated_at > :since
                        order by y, x
                        """)
                .param("userId", userId)
                .param("since", since.atOffset(ZoneOffset.UTC))
                .query((rs, rowNum) -> new TileRow(
                        rs.getInt("x"),
                        rs.getInt("y"),
                        rs.getBytes("mask"),
                        rs.getTimestamp("updated_at").toInstant()))
                .list();
    }

    public List<TileStat> findStats(long userId) {
        return jdbc.sql("select y, revealed_cells from fog_tiles where user_id = :userId")
                .param("userId", userId)
                .query((rs, rowNum) -> new TileStat(rs.getInt("y"), rs.getInt("revealed_cells")))
                .list();
    }
}

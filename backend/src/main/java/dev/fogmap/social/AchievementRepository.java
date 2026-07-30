package dev.fogmap.social;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class AchievementRepository {

    public record Definition(String code, String title, String metric, long threshold) {
    }

    public record Unlocked(String code, String title, long unlockedAt) {
    }

    private final JdbcClient jdbc;

    public AchievementRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Definition> all() {
        return jdbc.sql("select code, title, metric, threshold from achievements order by threshold")
                .query(Definition.class)
                .list();
    }

    /**
     * Выдаёт достижение. Повтор ничего не меняет — идемпотентность держится на первичном ключе,
     * а не на аккуратности вызывающего.
     */
    public int award(long userId, String code) {
        return jdbc.sql("""
                        insert into user_achievements (user_id, code)
                        values (:userId, :code)
                        on conflict (user_id, code) do nothing
                        """)
                .param("userId", userId)
                .param("code", code)
                .update();
    }

    public List<Unlocked> unlocked(long userId) {
        return jdbc.sql("""
                        select a.code, a.title, ua.unlocked_at
                        from user_achievements ua
                        join achievements a on a.code = ua.code
                        where ua.user_id = :userId
                        order by ua.unlocked_at
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new Unlocked(
                        rs.getString("code"),
                        rs.getString("title"),
                        rs.getTimestamp("unlocked_at").toInstant().toEpochMilli()))
                .list();
    }
}

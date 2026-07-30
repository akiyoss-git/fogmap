package dev.fogmap.social;

import java.util.List;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class LeaderboardRepository {

    public record Entry(long userId, String username, long areaM2) {
    }

    private final JdbcClient jdbc;

    public LeaderboardRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public List<Entry> global(int limit) {
        return jdbc.sql("""
                        select s.user_id, u.username, s.area_m2
                        from user_stats s
                        join users u on u.id = s.user_id
                        order by s.area_m2 desc, s.user_id
                        limit :limit
                        """)
                .param("limit", limit)
                .query(Entry.class)
                .list();
    }

    /** Друзья и сам пользователь: сравнивать себя со своим кругом — весь смысл этой вкладки. */
    public List<Entry> friends(long userId, int limit) {
        return jdbc.sql("""
                        select s.user_id, u.username, s.area_m2
                        from user_stats s
                        join users u on u.id = s.user_id
                        where s.user_id = :userId or s.user_id in (
                            select case when f.requester_id = :userId then f.addressee_id
                                        else f.requester_id end
                            from friendships f
                            where f.status = 'ACCEPTED'
                              and (f.requester_id = :userId or f.addressee_id = :userId)
                        )
                        order by s.area_m2 desc, s.user_id
                        limit :limit
                        """)
                .param("userId", userId)
                .param("limit", limit)
                .query(Entry.class)
                .list();
    }
}

package dev.fogmap.social;

import java.util.List;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class FriendshipRepository {

    public static final String PENDING = "PENDING";
    public static final String ACCEPTED = "ACCEPTED";

    public record FriendRow(long userId, String username, long areaM2) {
    }

    public record RequestRow(long requesterId, String username) {
    }

    private final JdbcClient jdbc;

    public FriendshipRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Заявка в одну сторону. Повторная от того же к тому же ничего не меняет. */
    public int request(long requesterId, long addresseeId) {
        return jdbc.sql("""
                        insert into friendships (requester_id, addressee_id, status)
                        values (:requester, :addressee, 'PENDING')
                        on conflict (requester_id, addressee_id) do nothing
                        """)
                .param("requester", requesterId)
                .param("addressee", addresseeId)
                .update();
    }

    /** Подтверждает заявку. Ноль означает, что заявки не было или она уже подтверждена. */
    public int accept(long requesterId, long addresseeId) {
        return jdbc.sql("""
                        update friendships set status = 'ACCEPTED'
                        where requester_id = :requester
                          and addressee_id = :addressee
                          and status = 'PENDING'
                        """)
                .param("requester", requesterId)
                .param("addressee", addresseeId)
                .update();
    }

    public boolean areFriends(long a, long b) {
        Integer count = jdbc.sql("""
                        select count(*) from friendships
                        where status = 'ACCEPTED'
                          and ((requester_id = :a and addressee_id = :b)
                            or (requester_id = :b and addressee_id = :a))
                        """)
                .param("a", a)
                .param("b", b)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }

    /** Дружба симметрична, поэтому собеседник берётся из той стороны, которой нет в запросе. */
    public List<FriendRow> friendsOf(long userId) {
        return jdbc.sql("""
                        select u.id, u.username, coalesce(s.area_m2, 0) as area_m2
                        from friendships f
                        join users u on u.id = case
                            when f.requester_id = :userId then f.addressee_id
                            else f.requester_id end
                        left join user_stats s on s.user_id = u.id
                        where f.status = 'ACCEPTED'
                          and (f.requester_id = :userId or f.addressee_id = :userId)
                        order by area_m2 desc
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new FriendRow(
                        rs.getLong("id"), rs.getString("username"), rs.getLong("area_m2")))
                .list();
    }

    public List<RequestRow> incomingRequests(long userId) {
        return jdbc.sql("""
                        select u.id, u.username from friendships f
                        join users u on u.id = f.requester_id
                        where f.addressee_id = :userId and f.status = 'PENDING'
                        order by f.created_at
                        """)
                .param("userId", userId)
                .query((rs, rowNum) -> new RequestRow(rs.getLong("id"), rs.getString("username")))
                .list();
    }

    public Optional<Long> findUserIdByUsername(String username) {
        return jdbc.sql("select id from users where username = :username")
                .param("username", username)
                .query(Long.class)
                .optional();
    }
}

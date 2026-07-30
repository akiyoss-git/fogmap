package dev.fogmap.auth;

import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
public class UserRepository {

    /** Строка таблицы users. Наружу в JSON не уходит — только через DTO. */
    public record UserRow(long id, String username, String passwordHash) {
    }

    private final JdbcClient jdbc;

    public UserRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public long insert(String username, String email, String passwordHash) {
        return jdbc.sql("""
                        insert into users (username, email, password_hash)
                        values (:username, :email, :passwordHash)
                        returning id
                        """)
                .param("username", username)
                .param("email", email)
                .param("passwordHash", passwordHash)
                .query(Long.class)
                .single();
    }

    public Optional<UserRow> findByUsername(String username) {
        return jdbc.sql("select id, username, password_hash from users where username = :username")
                .param("username", username)
                .query((rs, rowNum) -> new UserRow(
                        rs.getLong("id"), rs.getString("username"), rs.getString("password_hash")))
                .optional();
    }

    public int delete(long id) {
        return jdbc.sql("delete from users where id = :id").param("id", id).update();
    }

    public Optional<String> findUsernameById(long id) {
        return jdbc.sql("select username from users where id = :id")
                .param("id", id)
                .query(String.class)
                .optional();
    }

    public boolean exists(String username, String email) {
        Integer count = jdbc.sql("select count(*) from users where username = :username or email = :email")
                .param("username", username)
                .param("email", email)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}

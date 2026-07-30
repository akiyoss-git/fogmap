package dev.fogmap.auth;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Время параметров переводится в {@link java.time.OffsetDateTime}: драйвер Postgres не умеет
 * выводить SQL-тип для {@link Instant} и падает на «Can't infer the SQL type».
 */
@Repository
public class RefreshTokenRepository {

    private final JdbcClient jdbc;

    public RefreshTokenRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public void insert(long userId, String tokenHash, Instant expiresAt) {
        jdbc.sql("""
                        insert into refresh_tokens (user_id, token_hash, expires_at)
                        values (:userId, :tokenHash, :expiresAt)
                        """)
                .param("userId", userId)
                .param("tokenHash", tokenHash)
                .param("expiresAt", expiresAt.atOffset(ZoneOffset.UTC))
                .update();
    }

    /** Идентификатор владельца живого токена: не отозван и не просрочен. */
    public Optional<Long> findActiveOwner(String tokenHash) {
        return jdbc.sql("""
                        select user_id from refresh_tokens
                        where token_hash = :tokenHash
                          and revoked_at is null
                          and expires_at > now()
                        """)
                .param("tokenHash", tokenHash)
                .query(Long.class)
                .optional();
    }

    /** Владелец токена независимо от того, жив он или уже отозван. */
    public Optional<Long> findOwner(String tokenHash) {
        return jdbc.sql("select user_id from refresh_tokens where token_hash = :tokenHash")
                .param("tokenHash", tokenHash)
                .query(Long.class)
                .optional();
    }

    /**
     * Гасит все токены пользователя. Вызывается при попытке использовать уже отозванный токен:
     * такое бывает только если он утёк, и тогда безопаснее разлогинить всех, чем гадать, кто из
     * двоих настоящий.
     *
     * <p>Отдельная транзакция обязательна. Вызывающий сразу после этого бросает 401, откатывая
     * свою транзакцию, — и вместе с ней откатился бы отзыв. Токен вора умирал бы, а токен
     * владельца выживал, то есть защита не срабатывала бы вовсе.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public int revokeAllForUser(long userId) {
        return jdbc.sql("update refresh_tokens set revoked_at = now() where user_id = :userId and revoked_at is null")
                .param("userId", userId)
                .update();
    }

    /** Отзыв при ротации: обновлённая строка одна, повторный вызов вернёт 0. */
    public int revoke(String tokenHash) {
        return jdbc.sql("update refresh_tokens set revoked_at = now() where token_hash = :tokenHash and revoked_at is null")
                .param("tokenHash", tokenHash)
                .update();
    }
}

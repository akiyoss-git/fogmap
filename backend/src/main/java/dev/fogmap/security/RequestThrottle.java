package dev.fogmap.security;

import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ограничитель попыток по ключу — защита от перебора пароля.
 *
 * <p>Счётчик лежит в базе, а не в памяти процесса: при нескольких экземплярах сервера памятный
 * счётчик даёт по отдельному лимиту на каждый, и перебор раскладывается по инстансам.
 *
 * <p>Ключ по адресу берётся из {@code getRemoteAddr()}. За обратным прокси там окажется адрес
 * прокси — при развёртывании нужно либо настроить `server.forward-headers-strategy`, либо
 * ограничивать на самом прокси.
 */
@Component
public class RequestThrottle {

    private final JdbcClient jdbc;
    private final int maxFailures;
    private final Duration window;

    public RequestThrottle(
            JdbcClient jdbc,
            @Value("${fogmap.throttle.max-failures:10}") int maxFailures,
            @Value("${fogmap.throttle.window:PT15M}") Duration window) {
        this.jdbc = jdbc;
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Бросает 429, если по ключу превышен лимит. */
    public void check(String key) {
        Integer attempts = jdbc.sql("""
                        select attempts from login_attempts
                        where key = :key and window_started_at >= :windowStart
                        """)
                .param("key", key)
                .param("windowStart", windowStart())
                .query(Integer.class)
                .optional()
                .orElse(0);

        if (attempts >= maxFailures) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts");
        }
    }

    /**
     * Одним запросом: если прошлое окно истекло, счётчик начинается заново, иначе растёт.
     * Читать-потом-писать здесь нельзя — параллельные попытки затирали бы друг друга.
     */
    public void recordFailure(String key) {
        jdbc.sql("""
                        insert into login_attempts (key, attempts, window_started_at)
                        values (:key, 1, now())
                        on conflict (key) do update set
                            attempts = case
                                when login_attempts.window_started_at < :windowStart then 1
                                else login_attempts.attempts + 1 end,
                            window_started_at = case
                                when login_attempts.window_started_at < :windowStart then now()
                                else login_attempts.window_started_at end
                        """)
                .param("key", key)
                .param("windowStart", windowStart())
                .update();
    }

    /** Успех обнуляет счётчик: подобравший пароль с первой попытки и так уже внутри. */
    public void reset(String key) {
        jdbc.sql("delete from login_attempts where key = :key").param("key", key).update();
    }

    /** Иначе таблица копит по строке на каждый адрес, с которого когда-либо ошиблись. */
    @Scheduled(fixedDelayString = "PT1H")
    public void purgeExpired() {
        jdbc.sql("delete from login_attempts where window_started_at < :windowStart")
                .param("windowStart", windowStart())
                .update();
    }

    private OffsetDateTime windowStart() {
        return Instant.now().minus(window).atOffset(ZoneOffset.UTC);
    }
}

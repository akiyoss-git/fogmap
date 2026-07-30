package dev.fogmap.security;

import java.time.Duration;
import java.util.Iterator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ограничитель попыток по ключу — защита от перебора пароля.
 *
 * <p>Счётчик в памяти процесса: при нескольких экземплярах сервера злоумышленник получит лимит на
 * каждый из них. Для одного инстанса этого достаточно, для горизонтального масштабирования
 * счётчик придётся вынести в общее хранилище.
 *
 * <p>Ключ по адресу берётся из {@code getRemoteAddr()}. За обратным прокси там окажется адрес
 * прокси — при развёртывании нужно либо настроить `server.forward-headers-strategy`, либо
 * ограничивать на самом прокси.
 */
@Component
public class RequestThrottle {

    /** Больше стольких ключей не храним: иначе перебор по адресам сам станет утечкой памяти. */
    private static final int MAX_TRACKED_KEYS = 100_000;

    private final Map<String, Attempts> attempts = new ConcurrentHashMap<>();
    private final int maxFailures;
    private final Duration window;

    public RequestThrottle(
            @Value("${fogmap.throttle.max-failures:10}") int maxFailures,
            @Value("${fogmap.throttle.window:PT15M}") Duration window) {
        this.maxFailures = maxFailures;
        this.window = window;
    }

    /** Бросает 429, если по ключу превышен лимит. */
    public void check(String key) {
        Attempts current = attempts.get(key);
        if (current != null && !current.expired(window) && current.count >= maxFailures) {
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "too many attempts");
        }
    }

    public void recordFailure(String key) {
        purgeIfCrowded();
        attempts.compute(key, (ignored, existing) -> {
            if (existing == null || existing.expired(window)) return new Attempts();
            existing.count++;
            return existing;
        });
    }

    /** Успех обнуляет счётчик: подобравший пароль с первой попытки и так уже внутри. */
    public void reset(String key) {
        attempts.remove(key);
    }

    private void purgeIfCrowded() {
        if (attempts.size() < MAX_TRACKED_KEYS) return;
        Iterator<Map.Entry<String, Attempts>> iterator = attempts.entrySet().iterator();
        while (iterator.hasNext()) {
            if (iterator.next().getValue().expired(window)) iterator.remove();
        }
    }

    private static final class Attempts {
        private int count = 1;
        private final long startedAt = System.nanoTime();

        boolean expired(Duration window) {
            return System.nanoTime() - startedAt > window.toNanos();
        }
    }
}

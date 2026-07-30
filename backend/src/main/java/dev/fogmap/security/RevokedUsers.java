package dev.fogmap.security;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * Пользователи, чьи access-токены больше не принимаются.
 *
 * <p>Access-токен подписан и живёт 15 минут — отозвать его самому по себе нельзя. Поэтому после
 * удаления аккаунта идентификатор попадает сюда, и фильтр перестаёт пускать по уже выданным
 * токенам. Без этого удаливший аккаунт человек оставался бы доступен ещё четверть часа: у
 * укравшего телефон это ровно то окно, ради закрытия которого аккаунт и удаляют.
 *
 * <p>Запись живёт столько же, сколько сам токен: дольше держать бессмысленно, он и так протухнет.
 *
 * <p>Список в памяти процесса. При нескольких экземплярах сервера отзыв не разойдётся между ними —
 * тогда нужен общий кэш.
 */
@Component
public class RevokedUsers {

    private final Map<Long, Long> revokedUntil = new ConcurrentHashMap<>();
    private final Duration accessTtl;

    public RevokedUsers(@Value("${fogmap.jwt.access-ttl}") Duration accessTtl) {
        this.accessTtl = accessTtl;
    }

    public void revoke(long userId) {
        purgeExpired();
        revokedUntil.put(userId, System.nanoTime() + accessTtl.toNanos());
    }

    public boolean isRevoked(long userId) {
        Long until = revokedUntil.get(userId);
        if (until == null) return false;
        if (System.nanoTime() > until) {
            revokedUntil.remove(userId);
            return false;
        }
        return true;
    }

    private void purgeExpired() {
        long now = System.nanoTime();
        revokedUntil.entrySet().removeIf(entry -> now > entry.getValue());
    }
}

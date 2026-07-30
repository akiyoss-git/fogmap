package dev.fogmap.security;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * Существует ли ещё пользователь, которому выписан токен.
 *
 * <p>Access-токен подписан и живёт 15 минут — отозвать его самого по себе нельзя. Раньше здесь был
 * список отозванных в памяти процесса, но он не переживал ни перезапуск, ни второй экземпляр
 * сервера. Проверка по базе не имеет ни того, ни другого недостатка и заодно короче: удалённого
 * пользователя просто нет.
 *
 * <p>Цена — запрос на каждое обращение с токеном. При росте нагрузки сюда просится кэш с коротким
 * временем жизни, но кэш вернёт то самое окно, ради закрытия которого всё и делается, поэтому
 * заводить его стоит осознанно, а не заранее.
 */
@Component
public class ActiveUsers {

    private final JdbcClient jdbc;

    public ActiveUsers(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public boolean exists(long userId) {
        Integer count = jdbc.sql("select count(*) from users where id = :id")
                .param("id", userId)
                .query(Integer.class)
                .single();
        return count != null && count > 0;
    }
}

package dev.fogmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fogmap.auth.AuthDtos.LoginRequest;
import dev.fogmap.auth.AuthDtos.RegisterRequest;
import dev.fogmap.auth.AuthDtos.TokensResponse;
import java.util.Arrays;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/**
 * Отдельный класс с поднятым лимитом попыток.
 *
 * <p>Иначе измерение меряет не то: после нескольких неудач ограничитель отвечает 429 мгновенно, обе
 * ветки становятся одинаково быстрыми, и тест проходит независимо от того, исправлена утечка или
 * нет. Ровно на это я и напоролся при ручной проверке.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = "fogmap.throttle.max-failures=100000")
@Testcontainers
class LoginTimingTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    private TestRestTemplate rest;

    @Test
    @DisplayName("вход по несуществующему логину занимает столько же, сколько по существующему")
    void loginTimingDoesNotLeakUsernames() {
        String name = "timing" + System.nanoTime();
        assertEquals(
                HttpStatus.CREATED,
                rest.postForEntity(
                        "/auth/register",
                        new RegisterRequest(name, name + "@example.com", "password123"),
                        TokensResponse.class).getStatusCode());

        // Прогрев: первая проверка bcrypt тянет за собой инициализацию.
        medianMillis(name);
        medianMillis("нет-такого-" + name);

        long existing = medianMillis(name);
        long missing = medianMillis("нет-такого-" + name);

        // До исправления разрыв был 90 мс против 3: bcrypt считался только для существующих.
        // Порог щедрый — важен порядок величины, а не точное совпадение.
        assertTrue(
                Math.abs(existing - missing) < 40,
                "существующий " + existing + " мс, несуществующий " + missing + " мс");
    }

    private long medianMillis(String username) {
        long[] samples = new long[9];
        for (int i = 0; i < samples.length; i++) {
            long started = System.nanoTime();
            rest.postForEntity("/auth/login", new LoginRequest(username, "definitely-wrong"), String.class);
            samples[i] = (System.nanoTime() - started) / 1_000_000;
        }
        Arrays.sort(samples);
        return samples[samples.length / 2];
    }
}

package dev.fogmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fogmap.auth.AuthDtos.LoginRequest;
import dev.fogmap.auth.AuthDtos.RefreshRequest;
import dev.fogmap.auth.AuthDtos.RegisterRequest;
import dev.fogmap.auth.AuthDtos.TokensResponse;
import dev.fogmap.fog.FogDtos.SyncRequest;
import dev.fogmap.fog.FogDtos.SyncResponse;
import dev.fogmap.fog.FogDtos.TileUpload;
import dev.fogmap.fog.TileMath;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

/** Лимит занижен, чтобы проверять перебор без ожидания четверти часа. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@TestPropertySource(properties = {"fogmap.throttle.max-failures=5", "fogmap.throttle.window=PT5S"})
@Testcontainers
class SecurityHardeningTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    private record User(String name, TokensResponse tokens) {
    }

    private User newUser() {
        String name = "sec" + COUNTER.incrementAndGet();
        ResponseEntity<TokensResponse> response = rest.postForEntity(
                "/auth/register",
                new RegisterRequest(name, name + "@example.com", "password123"),
                TokensResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return new User(name, response.getBody());
    }

    private HttpHeaders bearer(String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(token);
        return headers;
    }

    @Test
    @DisplayName("перебор пароля упирается в 429, а не идёт бесконечно")
    void bruteForceIsThrottled() {
        User user = newUser();

        HttpStatus last = null;
        for (int i = 0; i < 8; i++) {
            last = (HttpStatus) rest.postForEntity(
                    "/auth/login", new LoginRequest(user.name(), "wrong" + i), String.class)
                    .getStatusCode();
        }
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, last);

        // Даже с верным паролем — пока лимит не истёк, вход закрыт.
        assertEquals(
                HttpStatus.TOO_MANY_REQUESTS,
                rest.postForEntity("/auth/login", new LoginRequest(user.name(), "password123"), String.class)
                        .getStatusCode());
    }

    @Test
    @DisplayName("повторное использование отозванного refresh гасит все сессии пользователя")
    void refreshReuseRevokesFamily() {
        User user = newUser();
        String stolen = user.tokens().refreshToken();

        ResponseEntity<TokensResponse> rotated = rest.postForEntity(
                "/auth/refresh", new RefreshRequest(stolen), TokensResponse.class);
        assertEquals(HttpStatus.OK, rotated.getStatusCode());
        assertNotNull(rotated.getBody());

        // Вор пытается использовать старый токен.
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                rest.postForEntity("/auth/refresh", new RefreshRequest(stolen), String.class).getStatusCode());

        // И теперь свежий токен настоящего владельца тоже мёртв — обоих выкинуло.
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                rest.postForEntity(
                        "/auth/refresh",
                        new RefreshRequest(rotated.getBody().refreshToken()),
                        String.class).getStatusCode());
    }

    @Test
    @DisplayName("удаление аккаунта уносит маску, статистику и токены")
    void deleteAccountWipesEverything() {
        User user = newUser();
        byte[] mask = new byte[TileMath.MASK_BYTES];
        Arrays.fill(mask, (byte) 0xFF);
        rest.exchange(
                "/fog/sync",
                HttpMethod.POST,
                new HttpEntity<>(
                        new SyncRequest(null, List.of(new TileUpload(9000, 5122, mask, null))),
                        bearer(user.tokens().accessToken())),
                SyncResponse.class);

        long userId = jdbc.sql("select id from users where username = :name")
                .param("name", user.name()).query(Long.class).single();
        assertTrue(countFor("fog_tiles", userId) > 0);

        ResponseEntity<Void> deleted = rest.exchange(
                "/auth/account",
                HttpMethod.DELETE,
                new HttpEntity<>(bearer(user.tokens().accessToken())),
                Void.class);
        assertEquals(HttpStatus.NO_CONTENT, deleted.getStatusCode());

        assertEquals(0, countFor("fog_tiles", userId));
        assertEquals(0, countFor("user_stats", userId));
        assertEquals(0, countFor("refresh_tokens", userId));
        assertEquals(0, countFor("user_achievements", userId));
        assertEquals(
                0,
                jdbc.sql("select count(*) from users where id = :id")
                        .param("id", userId).query(Integer.class).single());
    }

    @Test
    @DisplayName("удаление аккаунта закрыто без токена")
    void deleteAccountRequiresAuth() {
        assertEquals(
                HttpStatus.UNAUTHORIZED,
                rest.exchange("/auth/account", HttpMethod.DELETE, null, String.class).getStatusCode());
    }

    private int countFor(String table, long userId) {
        return jdbc.sql("select count(*) from " + table + " where user_id = :id")
                .param("id", userId).query(Integer.class).single();
    }
}

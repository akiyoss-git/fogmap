package dev.fogmap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import dev.fogmap.auth.AuthDtos.RegisterRequest;
import dev.fogmap.auth.AuthDtos.TokensResponse;
import dev.fogmap.fog.FogDtos.SyncRequest;
import dev.fogmap.fog.FogDtos.SyncResponse;
import dev.fogmap.fog.FogDtos.TileUpload;
import dev.fogmap.fog.TileMath;
import dev.fogmap.social.SocialDtos.AchievementDto;
import dev.fogmap.social.SocialDtos.FriendDto;
import dev.fogmap.social.SocialDtos.FriendRequestBody;
import dev.fogmap.social.SocialDtos.LeaderboardEntryDto;
import dev.fogmap.social.SocialDtos.UserStatsDto;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class SocialIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    @Autowired
    private JdbcClient jdbc;

    private record User(String name, String accessToken) {
    }

    private User newUser() {
        String name = "social" + COUNTER.incrementAndGet();
        ResponseEntity<TokensResponse> response = rest.postForEntity(
                "/auth/register",
                new RegisterRequest(name, name + "@example.com", "password123"),
                TokensResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return new User(name, response.getBody().accessToken());
    }

    private HttpHeaders auth(User user) {
        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(user.accessToken());
        return headers;
    }

    private <T> ResponseEntity<T> get(User user, String path, ParameterizedTypeReference<T> type) {
        return rest.exchange(path, HttpMethod.GET, new HttpEntity<>(auth(user)), type);
    }

    private ResponseEntity<Void> post(User user, String path, Object body) {
        return rest.exchange(path, HttpMethod.POST, new HttpEntity<>(body, auth(user)), Void.class);
    }

    /** Тайл, закрашенный целиком: 65536 ячеек, около 1.9 км² на широте Москвы. */
    private static TileUpload fullTile(int x, int y) {
        byte[] mask = new byte[TileMath.MASK_BYTES];
        Arrays.fill(mask, (byte) 0xFF);
        return new TileUpload(x, y, mask, null);
    }

    private void sync(User user, TileUpload... tiles) {
        ResponseEntity<SyncResponse> response = rest.exchange(
                "/fog/sync",
                HttpMethod.POST,
                new HttpEntity<>(new SyncRequest(null, List.of(tiles)), auth(user)),
                SyncResponse.class);
        assertEquals(HttpStatus.OK, response.getStatusCode());
    }

    @Test
    @DisplayName("достижение выдаётся ровно один раз при повторных синках")
    void achievementAwardedOnce() {
        User user = newUser();

        sync(user, fullTile(9903, 5122));
        sync(user, fullTile(9903, 5122));
        sync(user, fullTile(9903, 5122));

        List<AchievementDto> unlocked = get(
                user, "/achievements", new ParameterizedTypeReference<List<AchievementDto>>() {}).getBody();

        assertNotNull(unlocked);
        assertEquals(1, unlocked.size(), "ожидалось одно достижение, получено " + unlocked);
        assertEquals("area_1km", unlocked.get(0).code());
    }

    @Test
    @DisplayName("площадь чужого пользователя видна только после подтверждённой дружбы")
    void statsHiddenFromStrangers() {
        User alice = newUser();
        User bob = newUser();
        sync(bob, fullTile(9905, 5122));

        long bobId = jdbc.sql("select id from users where username = :name")
                .param("name", bob.name()).query(Long.class).single();

        ResponseEntity<UserStatsDto> beforeFriendship =
                rest.exchange("/users/" + bobId + "/stats", HttpMethod.GET,
                        new HttpEntity<>(auth(alice)), UserStatsDto.class);
        assertEquals(HttpStatus.FORBIDDEN, beforeFriendship.getStatusCode());

        // Одной заявки мало — нужно подтверждение второй стороны.
        post(alice, "/friends/requests", new FriendRequestBody(bob.name()));
        ResponseEntity<UserStatsDto> afterRequest =
                rest.exchange("/users/" + bobId + "/stats", HttpMethod.GET,
                        new HttpEntity<>(auth(alice)), UserStatsDto.class);
        assertEquals(HttpStatus.FORBIDDEN, afterRequest.getStatusCode());

        post(bob, "/friends/requests/accept", new FriendRequestBody(alice.name()));
        ResponseEntity<UserStatsDto> afterAccept =
                rest.exchange("/users/" + bobId + "/stats", HttpMethod.GET,
                        new HttpEntity<>(auth(alice)), UserStatsDto.class);
        assertEquals(HttpStatus.OK, afterAccept.getStatusCode());
        assertNotNull(afterAccept.getBody());
        assertTrue(afterAccept.getBody().areaM2() > 0);
    }

    @Test
    @DisplayName("список друзей симметричен и содержит площадь")
    void friendsListIsSymmetric() {
        User alice = newUser();
        User bob = newUser();
        sync(alice, fullTile(9907, 5122));

        post(alice, "/friends/requests", new FriendRequestBody(bob.name()));
        post(bob, "/friends/requests/accept", new FriendRequestBody(alice.name()));

        List<FriendDto> aliceFriends = get(
                alice, "/friends", new ParameterizedTypeReference<List<FriendDto>>() {}).getBody();
        List<FriendDto> bobFriends = get(
                bob, "/friends", new ParameterizedTypeReference<List<FriendDto>>() {}).getBody();

        assertNotNull(aliceFriends);
        assertNotNull(bobFriends);
        assertEquals(List.of(bob.name()), aliceFriends.stream().map(FriendDto::username).toList());
        assertEquals(List.of(alice.name()), bobFriends.stream().map(FriendDto::username).toList());
        assertTrue(bobFriends.get(0).areaM2() > 0, "в списке друзей должна быть площадь друга");
    }

    @Test
    @DisplayName("лидерборд друзей содержит только друзей и самого себя")
    void friendsLeaderboardIsScoped() {
        User alice = newUser();
        User bob = newUser();
        User stranger = newUser();
        sync(alice, fullTile(9909, 5122));
        sync(bob, fullTile(9910, 5122));
        sync(stranger, fullTile(9911, 5122));

        post(alice, "/friends/requests", new FriendRequestBody(bob.name()));
        post(bob, "/friends/requests/accept", new FriendRequestBody(alice.name()));

        List<LeaderboardEntryDto> board = get(
                alice,
                "/leaderboard?scope=friends",
                new ParameterizedTypeReference<List<LeaderboardEntryDto>>() {}).getBody();

        assertNotNull(board);
        List<String> names = board.stream().map(LeaderboardEntryDto::username).toList();
        assertEquals(2, names.size(), "ожидались только сам пользователь и его друг: " + names);
        assertTrue(names.contains(alice.name()));
        assertTrue(names.contains(bob.name()));
    }

    @Test
    @DisplayName("глобальный лидерборд на 10k пользователей отвечает быстрее 200 мс")
    void globalLeaderboardIsFast() {
        User user = newUser();
        jdbc.sql("""
                insert into users (username, email, password_hash)
                select 'bot' || i, 'bot' || i || '@example.com', 'x' from generate_series(1, 10000) i
                """).update();
        jdbc.sql("""
                insert into user_stats (user_id, area_m2, tiles_count)
                select id, (random() * 100000000)::bigint, 10 from users where username like 'bot%'
                """).update();

        // Первый вызов прогревает пул и JIT, замеряем второй.
        get(user, "/leaderboard", new ParameterizedTypeReference<List<LeaderboardEntryDto>>() {});

        long start = System.nanoTime();
        List<LeaderboardEntryDto> board = get(
                user, "/leaderboard", new ParameterizedTypeReference<List<LeaderboardEntryDto>>() {}).getBody();
        long millis = (System.nanoTime() - start) / 1_000_000;

        assertNotNull(board);
        assertEquals(100, board.size());
        assertEquals(1, board.get(0).rank());
        assertTrue(board.get(0).areaM2() >= board.get(99).areaM2(), "лидерборд должен быть отсортирован");
        assertTrue(millis < 200, "лидерборд отвечал " + millis + " мс");
    }
}

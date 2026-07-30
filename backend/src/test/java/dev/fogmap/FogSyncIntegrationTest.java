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
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Testcontainers
class FogSyncIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    private static final AtomicInteger COUNTER = new AtomicInteger();

    @Autowired
    private TestRestTemplate rest;

    private TokensResponse newUser() {
        int n = COUNTER.incrementAndGet();
        ResponseEntity<TokensResponse> response = rest.postForEntity(
                "/auth/register",
                new RegisterRequest("user" + n, "user" + n + "@example.com", "password123"),
                TokensResponse.class);
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertNotNull(response.getBody());
        return response.getBody();
    }

    private static byte[] mask(int firstByte, int bytes) {
        byte[] mask = new byte[TileMath.MASK_BYTES];
        for (int i = firstByte; i < firstByte + bytes; i++) {
            mask[i] = (byte) 0xFF;
        }
        return mask;
    }

    private ResponseEntity<SyncResponse> sync(String accessToken, SyncRequest request) {
        HttpHeaders headers = new HttpHeaders();
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return rest.exchange("/fog/sync", HttpMethod.POST, new HttpEntity<>(request, headers), SyncResponse.class);
    }

    @Test
    @DisplayName("завышенный revealedCells от клиента игнорируется — площадь считается из блоба")
    void inflatedCellCountIgnored() {
        TokensResponse tokens = newUser();
        // 8 байт по 8 бит = 64 ячейки, а клиент заявляет миллион.
        TileUpload upload = new TileUpload(9903, 5122, mask(0, 8), 1_000_000);

        SyncResponse body = sync(tokens.accessToken(), new SyncRequest(null, List.of(upload))).getBody();

        assertNotNull(body);
        long expected = Math.round(64 * TileMath.cellAreaM2(5122));
        assertEquals(expected, body.areaM2());
    }

    @Test
    @DisplayName("повторная отправка того же тайла не меняет площадь")
    void repeatedUploadIsIdempotent() {
        TokensResponse tokens = newUser();
        SyncRequest request = new SyncRequest(null, List.of(new TileUpload(9903, 5122, mask(0, 8), null)));

        long first = sync(tokens.accessToken(), request).getBody().areaM2();
        long second = sync(tokens.accessToken(), request).getBody().areaM2();
        long third = sync(tokens.accessToken(), request).getBody().areaM2();

        assertTrue(first > 0);
        assertEquals(first, second);
        assertEquals(first, third);
    }

    @Test
    @DisplayName("тайлы с двух устройств дают одинаковый результат в любом порядке")
    void mergeOrderDoesNotMatter() {
        TileUpload deviceA = new TileUpload(9903, 5122, mask(0, 8), null);
        TileUpload deviceB = new TileUpload(9903, 5122, mask(4, 8), null);

        TokensResponse first = newUser();
        sync(first.accessToken(), new SyncRequest(null, List.of(deviceA)));
        long areaAthenB = sync(first.accessToken(), new SyncRequest(null, List.of(deviceB))).getBody().areaM2();

        TokensResponse second = newUser();
        sync(second.accessToken(), new SyncRequest(null, List.of(deviceB)));
        long areaBthenA = sync(second.accessToken(), new SyncRequest(null, List.of(deviceA))).getBody().areaM2();

        assertEquals(areaAthenB, areaBthenA);
        // Перекрытие в 4 байта учтено один раз: 12 байт по 8 бит.
        assertEquals(Math.round(96 * TileMath.cellAreaM2(5122)), areaAthenB);
    }

    @Test
    @DisplayName("синк возвращает тайлы, изменённые после since")
    void syncReturnsTiles() {
        TokensResponse tokens = newUser();
        sync(tokens.accessToken(), new SyncRequest(null, List.of(new TileUpload(9903, 5122, mask(0, 8), null))));

        SyncResponse body = sync(tokens.accessToken(), new SyncRequest(0L, List.of())).getBody();

        assertNotNull(body);
        assertEquals(1, body.tiles().size());
        assertEquals(9903, body.tiles().get(0).x());
        assertEquals(TileMath.MASK_BYTES, body.tiles().get(0).mask().length);
    }

    @Test
    @DisplayName("без токена, с мусором и с refresh-токеном вместо access — 401")
    void authIsRequired() {
        TokensResponse tokens = newUser();
        SyncRequest request = new SyncRequest(null, List.of());

        assertEquals(HttpStatus.UNAUTHORIZED, sync(null, request).getStatusCode());
        assertEquals(HttpStatus.UNAUTHORIZED, sync("not-a-token", request).getStatusCode());
        // Refresh-токен непрозрачный, не JWT: подписи у него нет и проверку он не проходит.
        assertEquals(HttpStatus.UNAUTHORIZED, sync(tokens.refreshToken(), request).getStatusCode());
    }

    @Test
    @DisplayName("маска неверного размера и тайл вне мира отвергаются")
    void validationRejectsGarbage() {
        TokensResponse tokens = newUser();

        ResponseEntity<SyncResponse> shortMask = sync(
                tokens.accessToken(),
                new SyncRequest(null, List.of(new TileUpload(9903, 5122, new byte[10], null))));
        assertEquals(HttpStatus.BAD_REQUEST, shortMask.getStatusCode());

        ResponseEntity<SyncResponse> badTile = sync(
                tokens.accessToken(),
                new SyncRequest(null, List.of(new TileUpload(-1, 5122, mask(0, 1), null))));
        assertEquals(HttpStatus.BAD_REQUEST, badTile.getStatusCode());
    }

    @Test
    @DisplayName("refresh отдаёт новую пару и отзывает старый токен")
    void refreshRotates() {
        TokensResponse tokens = newUser();

        ResponseEntity<TokensResponse> refreshed = rest.postForEntity(
                "/auth/refresh",
                new dev.fogmap.auth.AuthDtos.RefreshRequest(tokens.refreshToken()),
                TokensResponse.class);
        assertEquals(HttpStatus.OK, refreshed.getStatusCode());
        assertNotNull(refreshed.getBody());

        // Повторное использование того же refresh-токена уже не проходит.
        ResponseEntity<String> reused = rest.postForEntity(
                "/auth/refresh",
                new dev.fogmap.auth.AuthDtos.RefreshRequest(tokens.refreshToken()),
                String.class);
        assertEquals(HttpStatus.UNAUTHORIZED, reused.getStatusCode());
    }
}

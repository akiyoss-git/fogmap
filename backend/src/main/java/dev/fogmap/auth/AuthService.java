package dev.fogmap.auth;

import dev.fogmap.auth.AuthDtos.TokensResponse;
import dev.fogmap.security.JwtService;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Optional;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthService {

    private static final Logger log = LoggerFactory.getLogger(AuthService.class);
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int REFRESH_TOKEN_BYTES = 32;

    private final UserRepository users;
    private final RefreshTokenRepository refreshTokens;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final Duration refreshTtl;

    /**
     * Хеш несуществующего пароля — чтобы сверка занимала время и когда пользователя нет.
     *
     * <p>Без него вход по несуществующему логину отвечал за 3 мс, а по существующему за 90: bcrypt
     * считается только во втором случае. Одинаковый код ответа при такой разнице ничего не скрывает,
     * логины перечисляются секундомером.
     */
    private final String dummyHash;

    public AuthService(
            UserRepository users,
            RefreshTokenRepository refreshTokens,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            @Value("${fogmap.jwt.refresh-ttl}") Duration refreshTtl) {
        this.users = users;
        this.refreshTokens = refreshTokens;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.refreshTtl = refreshTtl;

        byte[] filler = new byte[32];
        RANDOM.nextBytes(filler);
        this.dummyHash = passwordEncoder.encode(HexFormat.of().formatHex(filler));
    }

    @Transactional
    public TokensResponse register(String username, String email, String password) {
        if (users.exists(username, email)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "username or email already taken");
        }
        long userId = users.insert(username, email, passwordEncoder.encode(password));
        return issue(userId);
    }

    @Transactional
    public TokensResponse login(String username, String password) {
        Optional<UserRepository.UserRow> found = users.findByUsername(username);
        // Сверка выполняется всегда, даже против заглушки: одинаков должен быть не только код
        // ответа, но и время. Иначе перечисление логинов делается секундомером.
        String hash = found.map(UserRepository.UserRow::passwordHash).orElse(dummyHash);
        boolean matches = passwordEncoder.matches(password, hash);

        if (found.isEmpty() || !matches) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid credentials");
        }
        return issue(found.get().id());
    }

    /**
     * Ротация: старый refresh отзывается, выдаётся новая пара.
     *
     * <p>Повторное использование уже отозванного токена означает, что он у кого-то ещё: настоящий
     * владелец давно обменял его на новый. Отличить вора от владельца в этот момент нельзя,
     * поэтому гасим все токены пользователя — пусть оба войдут заново по паролю.
     */
    @Transactional
    public TokensResponse refresh(String refreshToken) {
        String hash = hash(refreshToken);

        Optional<Long> active = refreshTokens.findActiveOwner(hash);
        if (active.isEmpty()) {
            refreshTokens.findOwner(hash).ifPresent(owner -> {
                log.warn("Повторное использование отозванного refresh-токена, гашу сессии пользователя {}", owner);
                refreshTokens.revokeAllForUser(owner);
            });
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }

        long userId = active.get();
        if (refreshTokens.revoke(hash) == 0) {
            // Кто-то успел использовать этот же токен параллельно.
            refreshTokens.revokeAllForUser(userId);
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "invalid refresh token");
        }
        return issue(userId);
    }

    /** Каскады во внешних ключах уносят тайлы, статистику, дружбы, достижения и токены. */
    @Transactional
    public void deleteAccount(long userId) {
        if (users.delete(userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user");
        }
        // Refresh-токены унесло каскадом. Уже выданный access перестанет работать сам:
        // фильтр проверяет, что пользователь ещё существует.
    }

    private TokensResponse issue(long userId) {
        byte[] raw = new byte[REFRESH_TOKEN_BYTES];
        RANDOM.nextBytes(raw);
        String refreshToken = Base64.getUrlEncoder().withoutPadding().encodeToString(raw);

        refreshTokens.insert(userId, hash(refreshToken), Instant.now().plus(refreshTtl));
        return new TokensResponse(
                jwtService.issueAccessToken(userId), refreshToken, jwtService.accessTtlSeconds());
    }

    /** В базе лежит только хеш: утечка таблицы не даёт готовых токенов. */
    private static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 недоступен", e);
        }
    }
}

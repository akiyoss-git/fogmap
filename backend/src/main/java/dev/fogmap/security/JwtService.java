package dev.fogmap.security;

import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import javax.crypto.SecretKey;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class JwtService {

    private static final Logger log = LoggerFactory.getLogger(JwtService.class);

    private final SecretKey key;
    private final Duration accessTtl;

    public JwtService(
            @Value("${fogmap.jwt.secret:}") String secret,
            @Value("${fogmap.jwt.access-ttl}") Duration accessTtl) {
        this.accessTtl = accessTtl;
        this.key = secret.isBlank() ? randomKey() : Keys.hmacShaKeyFor(secret.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Ключа в репозитории нет (инвариант 6 в CLAUDE.md). Если он не задан снаружи, генерируем
     * случайный: разработка работает из коробки, а выданные токены живут только до перезапуска.
     */
    private static SecretKey randomKey() {
        log.warn("FOGMAP_JWT_SECRET не задан — сгенерирован случайный ключ, токены не переживут перезапуск");
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Keys.hmacShaKeyFor(bytes);
    }

    public String issueAccessToken(long userId) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(Long.toString(userId))
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(accessTtl)))
                .signWith(key)
                .compact();
    }

    public long accessTtlSeconds() {
        return accessTtl.toSeconds();
    }

    /** Идентификатор пользователя или null, если токен невалиден, просрочен или это не наш JWT. */
    public Long parseUserId(String token) {
        try {
            String subject = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload()
                    .getSubject();
            return Long.valueOf(subject);
        } catch (Exception e) {
            // Непрошедший проверку токен — обычная ситуация, а не ошибка сервера.
            return null;
        }
    }
}

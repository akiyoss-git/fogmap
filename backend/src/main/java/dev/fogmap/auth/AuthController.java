package dev.fogmap.auth;

import dev.fogmap.auth.AuthDtos.LoginRequest;
import dev.fogmap.auth.AuthDtos.RefreshRequest;
import dev.fogmap.auth.AuthDtos.RegisterRequest;
import dev.fogmap.auth.AuthDtos.TokensResponse;
import dev.fogmap.security.RequestThrottle;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import java.util.Locale;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/auth")
public class AuthController {

    private final AuthService authService;
    private final RequestThrottle throttle;

    public AuthController(AuthService authService, RequestThrottle throttle) {
        this.authService = authService;
        this.throttle = throttle;
    }

    /**
     * Ограничивается только по адресу и только на конфликт имени.
     *
     * <p>Успешные регистрации не считаем: перебор существующих логинов идёт через 409, а не через
     * создание аккаунтов. Массовое создание аккаунтов — другая задача (спам), и решается она
     * подтверждением почты, а не этим счётчиком.
     */
    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    public TokensResponse register(HttpServletRequest http, @Valid @RequestBody RegisterRequest request) {
        String addressKey = "register-ip:" + http.getRemoteAddr();
        throttle.check(addressKey);
        try {
            return authService.register(request.username(), request.email(), request.password());
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.CONFLICT) throttle.recordFailure(addressKey);
            throw e;
        }
    }

    /** Считаем неудачи и по адресу, и по логину: иначе перебор с ботнета обходит лимит. */
    @PostMapping("/login")
    public TokensResponse login(HttpServletRequest http, @Valid @RequestBody LoginRequest request) {
        // Пространства ключей разные: иначе чей-то забытый пароль закрывает регистрацию всем,
        // кто сидит за тем же адресом.
        String addressKey = "login-ip:" + http.getRemoteAddr();
        String userKey = "login-user:" + request.username().toLowerCase(Locale.ROOT);
        throttle.check(addressKey);
        throttle.check(userKey);

        try {
            TokensResponse tokens = authService.login(request.username(), request.password());
            throttle.reset(addressKey);
            throttle.reset(userKey);
            return tokens;
        } catch (ResponseStatusException e) {
            if (e.getStatusCode() == HttpStatus.UNAUTHORIZED) {
                throttle.recordFailure(addressKey);
                throttle.recordFailure(userKey);
            }
            throw e;
        }
    }

    @PostMapping("/refresh")
    public TokensResponse refresh(@Valid @RequestBody RefreshRequest request) {
        return authService.refresh(request.refreshToken());
    }

    /**
     * Удаление аккаунта вместе со всеми данными.
     *
     * <p>Маска тумана — это подробная история перемещений, и способ её стереть обязателен.
     * Каскады во внешних ключах уносят тайлы, статистику, дружбы, достижения и токены.
     */
    @DeleteMapping("/account")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteAccount(Authentication authentication) {
        authService.deleteAccount((Long) authentication.getPrincipal());
    }
}

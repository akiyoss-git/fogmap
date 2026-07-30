package dev.fogmap.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public final class AuthDtos {

    private AuthDtos() {
    }

    public record RegisterRequest(
            @NotBlank @Size(min = 3, max = 32) String username,
            @NotBlank @Email @Size(max = 255) String email,
            @NotBlank @Size(min = 8, max = 128) String password) {
    }

    public record LoginRequest(
            @NotBlank String username,
            @NotBlank String password) {
    }

    public record RefreshRequest(@NotBlank String refreshToken) {
    }

    public record TokensResponse(String accessToken, String refreshToken, long expiresInSeconds) {
    }
}

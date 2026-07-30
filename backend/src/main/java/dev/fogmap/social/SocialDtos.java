package dev.fogmap.social;

import jakarta.validation.constraints.NotBlank;

public final class SocialDtos {

    private SocialDtos() {
    }

    public record FriendRequestBody(@NotBlank String username) {
    }

    public record FriendDto(long userId, String username, long areaM2) {
    }

    public record PendingRequestDto(long userId, String username) {
    }

    public record LeaderboardEntryDto(int rank, long userId, String username, long areaM2) {
    }

    public record AchievementDto(String code, String title, long unlockedAt) {
    }

    public record UserStatsDto(long userId, String username, long areaM2) {
    }
}

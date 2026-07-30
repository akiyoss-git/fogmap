package dev.fogmap.social;

import dev.fogmap.social.SocialDtos.AchievementDto;
import dev.fogmap.social.SocialDtos.FriendDto;
import dev.fogmap.social.SocialDtos.FriendRequestBody;
import dev.fogmap.social.SocialDtos.LeaderboardEntryDto;
import dev.fogmap.social.SocialDtos.PendingRequestDto;
import dev.fogmap.social.SocialDtos.UserStatsDto;
import jakarta.validation.Valid;
import java.util.ArrayList;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class SocialController {

    private static final int LEADERBOARD_LIMIT = 100;

    private final FriendService friends;
    private final LeaderboardRepository leaderboard;
    private final AchievementService achievements;

    public SocialController(
            FriendService friends,
            LeaderboardRepository leaderboard,
            AchievementService achievements) {
        this.friends = friends;
        this.leaderboard = leaderboard;
        this.achievements = achievements;
    }

    private static long userId(Authentication authentication) {
        return (Long) authentication.getPrincipal();
    }

    @PostMapping("/friends/requests")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void request(Authentication auth, @Valid @RequestBody FriendRequestBody body) {
        friends.request(userId(auth), body.username());
    }

    @PostMapping("/friends/requests/accept")
    public void accept(Authentication auth, @Valid @RequestBody FriendRequestBody body) {
        friends.accept(userId(auth), body.username());
    }

    @GetMapping("/friends")
    public List<FriendDto> friends(Authentication auth) {
        return friends.friends(userId(auth));
    }

    @GetMapping("/friends/requests")
    public List<PendingRequestDto> incoming(Authentication auth) {
        return friends.incoming(userId(auth));
    }

    @GetMapping("/users/{id}/stats")
    public UserStatsDto stats(Authentication auth, @PathVariable long id) {
        return friends.statsOf(userId(auth), id);
    }

    @GetMapping("/leaderboard")
    public List<LeaderboardEntryDto> leaderboard(
            Authentication auth,
            @RequestParam(defaultValue = "global") String scope) {
        List<LeaderboardRepository.Entry> entries = "friends".equals(scope)
                ? leaderboard.friends(userId(auth), LEADERBOARD_LIMIT)
                : leaderboard.global(LEADERBOARD_LIMIT);

        List<LeaderboardEntryDto> result = new ArrayList<>(entries.size());
        for (int i = 0; i < entries.size(); i++) {
            LeaderboardRepository.Entry entry = entries.get(i);
            result.add(new LeaderboardEntryDto(i + 1, entry.userId(), entry.username(), entry.areaM2()));
        }
        return result;
    }

    @GetMapping("/achievements")
    public List<AchievementDto> achievements(Authentication auth) {
        return achievements.unlocked(userId(auth)).stream()
                .map(it -> new AchievementDto(it.code(), it.title(), it.unlockedAt()))
                .toList();
    }
}

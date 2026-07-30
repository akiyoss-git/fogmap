package dev.fogmap.social;

import dev.fogmap.auth.UserRepository;
import dev.fogmap.fog.UserStatsRepository;
import dev.fogmap.social.SocialDtos.FriendDto;
import dev.fogmap.social.SocialDtos.PendingRequestDto;
import dev.fogmap.social.SocialDtos.UserStatsDto;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class FriendService {

    private final FriendshipRepository friendships;
    private final UserRepository users;
    private final UserStatsRepository stats;

    public FriendService(FriendshipRepository friendships, UserRepository users, UserStatsRepository stats) {
        this.friendships = friendships;
        this.users = users;
        this.stats = stats;
    }

    @Transactional
    public void request(long userId, String username) {
        long target = resolve(username);
        if (target == userId) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "cannot befriend yourself");
        }
        friendships.request(userId, target);
    }

    @Transactional
    public void accept(long userId, String requesterUsername) {
        long requester = resolve(requesterUsername);
        if (friendships.accept(requester, userId) == 0) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "no pending request");
        }
    }

    public List<FriendDto> friends(long userId) {
        return friendships.friendsOf(userId).stream()
                .map(row -> new FriendDto(row.userId(), row.username(), row.areaM2()))
                .toList();
    }

    public List<PendingRequestDto> incoming(long userId) {
        return friendships.incomingRequests(userId).stream()
                .map(row -> new PendingRequestDto(row.requesterId(), row.username()))
                .toList();
    }

    /**
     * Площадь чужого пользователя видна только друзьям.
     *
     * <p>В глобальном лидерборде площадь показывается всем — но это агрегат, к которому человек
     * присоединяется добровольно, а точечный запрос по чужому идентификатору закрыт.
     */
    public UserStatsDto statsOf(long viewerId, long targetId) {
        if (viewerId != targetId && !friendships.areFriends(viewerId, targetId)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "not friends");
        }
        String username = users.findUsernameById(targetId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));
        long area = stats.find(targetId).map(UserStatsRepository.StatsRow::areaM2).orElse(0L);
        return new UserStatsDto(targetId, username, area);
    }

    private long resolve(String username) {
        return friendships.findUserIdByUsername(username)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "no such user"));
    }
}

package dev.fogmap.data.api

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Всё, что показывает социальный экран, одним запросом на открытие. */
class SocialSnapshot(
    val friends: List<Friend>,
    val incoming: List<PendingRequest>,
    val leaderboard: List<LeaderboardEntry>,
    val achievements: List<Achievement>,
)

enum class LeaderboardScope(val value: String) { GLOBAL("global"), FRIENDS("friends") }

class SocialRepository internal constructor(
    private val api: FogmapApi,
    private val guard: AuthGuard,
) {

    suspend fun load(scope: LeaderboardScope): SocialSnapshot = withContext(Dispatchers.IO) {
        SocialSnapshot(
            friends = guard.call { api.friends(it) },
            incoming = guard.call { api.incomingRequests(it) },
            leaderboard = guard.call { api.leaderboard(it, scope.value) },
            achievements = guard.call { api.achievements(it) },
        )
    }

    suspend fun requestFriend(username: String) = withContext(Dispatchers.IO) {
        val response = guard.call { api.requestFriend(it, FriendRequestBody(username)) }
        if (!response.isSuccessful) error("не удалось отправить заявку: ${response.code()}")
    }

    suspend fun acceptFriend(username: String) = withContext(Dispatchers.IO) {
        val response = guard.call { api.acceptFriend(it, FriendRequestBody(username)) }
        if (!response.isSuccessful) error("не удалось принять заявку: ${response.code()}")
    }
}

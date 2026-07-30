package dev.fogmap.data.api

import kotlinx.serialization.Serializable

@Serializable
data class Friend(val userId: Long, val username: String, val areaM2: Long)

@Serializable
data class PendingRequest(val userId: Long, val username: String)

@Serializable
data class LeaderboardEntry(
    val rank: Int,
    val userId: Long,
    val username: String,
    val areaM2: Long,
)

@Serializable
data class Achievement(val code: String, val title: String, val unlockedAt: Long)

@Serializable
internal data class FriendRequestBody(val username: String)

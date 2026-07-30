package dev.fogmap.data.api

import kotlinx.serialization.Serializable
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.Query

@Serializable
internal data class RegisterRequest(val username: String, val email: String, val password: String)

@Serializable
internal data class LoginRequest(val username: String, val password: String)

@Serializable
internal data class RefreshRequest(val refreshToken: String)

@Serializable
internal data class TokensResponse(
    val accessToken: String,
    val refreshToken: String,
    val expiresInSeconds: Long,
)

/**
 * Маска ходит строкой base64, а не массивом чисел: на сервере это `byte[]`, который Jackson
 * сериализует именно так. kotlinx.serialization по умолчанию развернул бы `ByteArray` в список
 * чисел, и форматы бы не сошлись.
 */
@Serializable
internal data class TileUploadDto(
    val x: Int,
    val y: Int,
    val mask: String,
    val revealedCells: Int,
)

@Serializable
internal data class SyncRequestDto(val since: Long?, val tiles: List<TileUploadDto>)

@Serializable
internal data class TileDownloadDto(val x: Int, val y: Int, val mask: String, val updatedAt: Long)

@Serializable
internal data class SyncResponseDto(
    val areaM2: Long,
    val tilesCount: Int,
    val tiles: List<TileDownloadDto>,
    val serverTime: Long,
)

@Serializable
internal data class RouteRequestDto(
    val fromLat: Double,
    val fromLon: Double,
    val toLat: Double,
    val toLon: Double,
)

@Serializable
internal data class PointDto(val lat: Double, val lon: Double)

@Serializable
internal data class RouteResponseDto(val distanceM: Double, val points: List<PointDto>)

@Serializable
internal data class ObstacleTileDto(val x: Int, val y: Int, val mask: String)

@Serializable
internal data class ObstacleTilesDto(val tiles: List<ObstacleTileDto>)

internal interface FogmapApi {

    @POST("auth/register")
    suspend fun register(@Body body: RegisterRequest): TokensResponse

    @POST("auth/login")
    suspend fun login(@Body body: LoginRequest): TokensResponse

    @POST("auth/refresh")
    suspend fun refresh(@Body body: RefreshRequest): TokensResponse

    @DELETE("auth/account")
    suspend fun deleteAccount(@Header("Authorization") bearer: String): Response<Unit>

    @POST("fog/sync")
    suspend fun sync(@Header("Authorization") bearer: String, @Body body: SyncRequestDto): SyncResponseDto

    @POST("friends/requests")
    suspend fun requestFriend(
        @Header("Authorization") bearer: String,
        @Body body: FriendRequestBody,
    ): Response<Unit>

    @POST("friends/requests/accept")
    suspend fun acceptFriend(
        @Header("Authorization") bearer: String,
        @Body body: FriendRequestBody,
    ): Response<Unit>

    @GET("friends")
    suspend fun friends(@Header("Authorization") bearer: String): List<Friend>

    @GET("friends/requests")
    suspend fun incomingRequests(@Header("Authorization") bearer: String): List<PendingRequest>

    @GET("leaderboard")
    suspend fun leaderboard(
        @Header("Authorization") bearer: String,
        @Query("scope") scope: String,
    ): List<LeaderboardEntry>

    @GET("achievements")
    suspend fun achievements(@Header("Authorization") bearer: String): List<Achievement>

    @POST("routes")
    suspend fun route(
        @Header("Authorization") bearer: String,
        @Body body: RouteRequestDto,
    ): RouteResponseDto

    @GET("obstacles")
    suspend fun obstacles(
        @Header("Authorization") bearer: String,
        @Query("minX") minX: Int,
        @Query("minY") minY: Int,
        @Query("maxX") maxX: Int,
        @Query("maxY") maxY: Int,
    ): ObstacleTilesDto
}

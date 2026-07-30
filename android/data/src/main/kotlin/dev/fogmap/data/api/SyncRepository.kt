package dev.fogmap.data.api

import android.util.Base64
import dev.fogmap.core.fog.FogTile
import dev.fogmap.core.fog.TileId
import dev.fogmap.data.DirtyTile
import dev.fogmap.data.FogRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Что синк принёс с сервера: тайлы для слияния в маску и посчитанная сервером площадь. */
class SyncResult(val serverTiles: List<Pair<TileId, FogTile>>, val areaM2: Long, val uploaded: Int)

class SyncRepository internal constructor(
    private val api: FogmapApi,
    private val tokens: TokenStore,
    private val fog: FogRepository,
    private val guard: AuthGuard,
) {

    val isAuthenticated: Boolean get() = tokens.isAuthenticated

    suspend fun register(username: String, email: String, password: String) = withContext(Dispatchers.IO) {
        tokens.save(api.register(RegisterRequest(username, email, password)))
    }

    suspend fun login(username: String, password: String) = withContext(Dispatchers.IO) {
        tokens.save(api.login(LoginRequest(username, password)))
    }

    fun logout() = tokens.clear()

    /**
     * Удаляет аккаунт на сервере и стирает локальную маску.
     *
     * Маска — это подробная история перемещений, и «удалить аккаунт», оставляющее её на устройстве,
     * было бы половинчатым обещанием.
     */
    suspend fun deleteAccount() = withContext(Dispatchers.IO) {
        val response = guard.call { api.deleteAccount(it) }
        if (!response.isSuccessful) error("не удалось удалить аккаунт: ${response.code()}")
        fog.deleteAll()
        tokens.clear()
    }

    /**
     * Отправляет накопленные тайлы и забирает чужие.
     *
     * Флаг снимается только после успешного ответа: оборвался запрос — тайлы останутся
     * неотправленными и уедут в следующий раз.
     */
    suspend fun sync(): SyncResult = withContext(Dispatchers.IO) {
        val dirty = fog.loadDirty(MAX_TILES_PER_SYNC)
        val request = SyncRequestDto(
            since = tokens.lastSyncAt.takeIf { it > 0 },
            tiles = dirty.map {
                TileUploadDto(
                    x = it.id.x,
                    y = it.id.y,
                    mask = Base64.encodeToString(it.mask, Base64.NO_WRAP),
                    revealedCells = it.revealedCells,
                )
            },
        )

        val response = guard.call { bearer -> api.sync(bearer, request) }

        fog.markSynced(dirty)
        tokens.lastSyncAt = response.serverTime

        val serverTiles = response.tiles.map { dto ->
            TileId(dto.x, dto.y) to FogTile.fromBytes(Base64.decode(dto.mask, Base64.NO_WRAP))
        }
        fog.saveFromServer(serverTiles)

        SyncResult(serverTiles, response.areaM2, dirty.size)
    }

    private companion object {
        /** Столько же, сколько принимает сервер за один запрос. */
        const val MAX_TILES_PER_SYNC = 512
    }
}

package dev.fogmap.data

import android.util.Base64
import dev.fogmap.core.fog.FogTile
import dev.fogmap.core.fog.TileId
import dev.fogmap.data.api.AuthGuard
import dev.fogmap.data.api.FogmapApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class ObstacleRepository internal constructor(
    private val dao: ObstacleTileDao,
    private val api: FogmapApi,
    private val guard: AuthGuard,
) {

    suspend fun loadStored(): List<Pair<TileId, FogTile>> = withContext(Dispatchers.IO) {
        dao.loadAll().map { TileId(it.x, it.y) to FogTile.fromBytes(it.mask) }
    }

    suspend fun storedKeys(): Set<TileId> = withContext(Dispatchers.IO) {
        dao.loadKeys().map { TileId(it.x, it.y) }.toSet()
    }

    /**
     * Тянет прямоугольник тайлов и складывает в базу. Возвращает то, что пришло.
     *
     * Пустой ответ — нормально: в тайле может не быть ни одного здания, и сервер такие не шлёт.
     */
    suspend fun fetch(minX: Int, minY: Int, maxX: Int, maxY: Int): List<Pair<TileId, FogTile>> =
        withContext(Dispatchers.IO) {
            val response = guard.call { api.obstacles(it, minX, minY, maxX, maxY) }
            val tiles = response.tiles.map { dto ->
                TileId(dto.x, dto.y) to FogTile.fromBytes(Base64.decode(dto.mask, Base64.NO_WRAP))
            }
            dao.upsert(
                response.tiles.map {
                    ObstacleTileEntity(it.x, it.y, Base64.decode(it.mask, Base64.NO_WRAP))
                },
            )
            tiles
        }
}

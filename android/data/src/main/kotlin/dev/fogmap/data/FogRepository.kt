package dev.fogmap.data

import dev.fogmap.core.fog.FogTile
import dev.fogmap.core.fog.TileId
import dev.fogmap.core.fog.TileSnapshot
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Тайл, ожидающий отправки на сервер. */
class DirtyTile(val id: TileId, val mask: ByteArray, val revealedCells: Int, val updatedAt: Long)

class FogRepository internal constructor(private val dao: FogTileDao) {

    /**
     * Читает сохранённые тайлы. Складывать их в [dev.fogmap.core.fog.FogMask] должен вызывающий,
     * на своём потоке: маска не потокобезопасна.
     */
    suspend fun loadTiles(): List<Pair<TileId, FogTile>> = withContext(Dispatchers.IO) {
        dao.loadAll().map { TileId(it.x, it.y) to FogTile.fromBytes(it.mask) }
    }

    /**
     * Принимает уже сериализованные тайлы, а не саму маску: разбор маски на фоновом потоке
     * гонялся бы с её изменением на main.
     */
    suspend fun save(snapshots: List<TileSnapshot>) = withContext(Dispatchers.IO) {
        if (snapshots.isEmpty()) return@withContext
        val now = System.currentTimeMillis()
        dao.upsert(
            snapshots.map {
                FogTileEntity(
                    x = it.id.x,
                    y = it.id.y,
                    mask = it.mask,
                    revealedCells = it.revealedCells,
                    updatedAt = now,
                    dirty = true,
                )
            },
        )
    }

    /** Стирает локальную маску. Только для удаления аккаунта. */
    suspend fun deleteAll() = withContext(Dispatchers.IO) { dao.deleteAll() }

    suspend fun loadDirty(limit: Int): List<DirtyTile> = withContext(Dispatchers.IO) {
        dao.loadDirty(limit).map {
            DirtyTile(TileId(it.x, it.y), it.mask, it.revealedCells, it.updatedAt)
        }
    }

    suspend fun markSynced(tiles: List<DirtyTile>) = withContext(Dispatchers.IO) {
        tiles.forEach { dao.clearDirty(it.id.x, it.id.y, it.updatedAt) }
    }

    suspend fun saveFromServer(tiles: List<Pair<TileId, FogTile>>) = withContext(Dispatchers.IO) {
        val now = System.currentTimeMillis()
        tiles.forEach { (id, tile) ->
            dao.upsertFromServer(id.x, id.y, tile.toBytes(), tile.revealedCells, now)
        }
    }
}

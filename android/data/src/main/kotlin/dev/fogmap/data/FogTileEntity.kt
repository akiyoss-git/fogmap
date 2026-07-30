package dev.fogmap.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

@Entity(tableName = "fog_tile", primaryKeys = ["x", "y"])
internal class FogTileEntity(
    val x: Int,
    val y: Int,
    /** Битовая маска тайла, 8192 байта. */
    val mask: ByteArray,
    /**
     * Денормализованный popcount маски: чтобы считать площадь и лидерборд, не разбирая блобы.
     * Сервер пересчитывает его сам и этому значению не доверяет.
     */
    val revealedCells: Int,
    val updatedAt: Long,
    /** Тайл изменился локально и ещё не уехал на сервер. */
    val dirty: Boolean = true,
)

@Dao
internal interface FogTileDao {

    @Query("SELECT * FROM fog_tile")
    suspend fun loadAll(): List<FogTileEntity>

    @Upsert
    suspend fun upsert(tiles: List<FogTileEntity>)

    @Query("DELETE FROM fog_tile")
    suspend fun deleteAll()

    @Query("SELECT * FROM fog_tile WHERE dirty = 1 LIMIT :limit")
    suspend fun loadDirty(limit: Int): List<FogTileEntity>

    /**
     * Снимает флаг только если тайл с тех пор не менялся: иначе вскрытие, случившееся во время
     * синка, потерялось бы до следующего изменения этого тайла.
     */
    @Query("UPDATE fog_tile SET dirty = 0 WHERE x = :x AND y = :y AND updatedAt = :updatedAt")
    suspend fun clearDirty(x: Int, y: Int, updatedAt: Long)

    /**
     * Тайл, пришедший с сервера. Флаг `dirty` у существующей строки не трогается: локальные
     * изменения могут быть ещё не отправлены.
     */
    @Query(
        """
        INSERT INTO fog_tile (x, y, mask, revealedCells, updatedAt, dirty)
        VALUES (:x, :y, :mask, :revealedCells, :updatedAt, 0)
        ON CONFLICT(x, y) DO UPDATE SET
            mask = :mask,
            revealedCells = :revealedCells,
            updatedAt = :updatedAt
        """,
    )
    suspend fun upsertFromServer(x: Int, y: Int, mask: ByteArray, revealedCells: Int, updatedAt: Long)
}

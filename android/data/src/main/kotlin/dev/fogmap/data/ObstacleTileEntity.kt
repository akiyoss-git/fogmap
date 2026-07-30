package dev.fogmap.data

import androidx.room.Dao
import androidx.room.Entity
import androidx.room.Query
import androidx.room.Upsert

/**
 * Растр зданий: тот же формат, что у маски тумана, бит означает «здесь стена».
 *
 * Данные общие для всех пользователей и приходят с сервера, поэтому флага «не отправлено» здесь
 * нет — этот растр только читается.
 */
@Entity(tableName = "obstacle_tile", primaryKeys = ["x", "y"])
internal class ObstacleTileEntity(
    val x: Int,
    val y: Int,
    val mask: ByteArray,
)

@Dao
internal interface ObstacleTileDao {

    @Query("SELECT * FROM obstacle_tile")
    suspend fun loadAll(): List<ObstacleTileEntity>

    @Query("SELECT x, y FROM obstacle_tile")
    suspend fun loadKeys(): List<TileKey>

    @Upsert
    suspend fun upsert(tiles: List<ObstacleTileEntity>)
}

internal data class TileKey(val x: Int, val y: Int)

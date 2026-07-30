package dev.fogmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(entities = [FogTileEntity::class, ObstacleTileEntity::class], version = 3)
internal abstract class FogDatabase : RoomDatabase() {

    abstract fun fogTiles(): FogTileDao

    abstract fun obstacleTiles(): ObstacleTileDao

    companion object {
        /**
         * Значение по умолчанию — 1: всё, что накопилось до появления синхронизации, считается
         * неотправленным и уедет на сервер при первом же синке.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE fog_tile ADD COLUMN dirty INTEGER NOT NULL DEFAULT 1")
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS obstacle_tile (
                        x INTEGER NOT NULL,
                        y INTEGER NOT NULL,
                        mask BLOB NOT NULL,
                        PRIMARY KEY (x, y)
                    )
                    """.trimIndent(),
                )
            }
        }

        fun open(context: Context): FogDatabase =
            Room.databaseBuilder(context, FogDatabase::class.java, "fogmap.db")
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
    }
}

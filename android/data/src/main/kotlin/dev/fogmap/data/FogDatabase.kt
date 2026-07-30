package dev.fogmap.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(entities = [FogTileEntity::class, ObstacleTileEntity::class], version = 3)
internal abstract class FogDatabase : RoomDatabase() {

    abstract fun fogTiles(): FogTileDao

    abstract fun obstacleTiles(): ObstacleTileDao

    companion object {

        init {
            // SQLCipher не грузит свою нативную часть сам: без этого падение в рантайме при
            // первом же обращении к базе, а сборка при этом проходит.
            System.loadLibrary("sqlcipher")
        }
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

        /**
         * База шифруется SQLCipher.
         *
         * Файл называется иначе, чем прежняя открытая база: та остаётся на диске нетронутой и
         * просто удаляется. Переносить из неё нечего — маска и так лежит на сервере и вернётся
         * первой же синхронизацией, а у не заходившего пользователя её и не было.
         */
        fun open(context: Context): FogDatabase {
            context.deleteDatabase(LEGACY_NAME)
            return Room.databaseBuilder(context, FogDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(DatabaseKey.obtain(context)))
                .addMigrations(MIGRATION_1_2, MIGRATION_2_3)
                .build()
        }

        private const val NAME = "fogmap-secure.db"
        private const val LEGACY_NAME = "fogmap.db"
    }
}

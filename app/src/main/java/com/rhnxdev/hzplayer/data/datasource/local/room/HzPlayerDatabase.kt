package com.rhnxdev.hzplayer.data.datasource.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.BrowserHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlaybackPositionDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlayHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.ServerConfigDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.StreamHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.BrowserHistoryEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlaybackPositionEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlayHistoryEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.ServerConfigEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.StreamHistoryEntity

@Database(
    entities = [
        MediaEntity::class,
        ServerConfigEntity::class,
        StreamHistoryEntity::class,
        PlaybackPositionEntity::class,
        BrowserHistoryEntity::class,
        PlayHistoryEntity::class,
    ],
    version = 7,
    // Schema exported to app/schemas so versioned Migrations can be authored and
    // reviewed in source control. exportSchema=false + fallbackToDestructiveMigration()
    // silently wiped all saved servers / resume positions / history on every bump.
    exportSchema = true,
)
abstract class HzPlayerDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun streamHistoryDao(): StreamHistoryDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
    abstract fun browserHistoryDao(): BrowserHistoryDao
    abstract fun playHistoryDao(): PlayHistoryDao

    companion object {
        /** Migration 3→4: add indices on media and stream_history tables. */
        val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                // media table indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_mediaType` ON `media` (`mediaType`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_uri` ON `media` (`uri`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_album` ON `media` (`album`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_media_artist` ON `media` (`artist`)")
                // stream_history table indices
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_history_url` ON `stream_history` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_stream_history_isFavorite` ON `stream_history` (`isFavorite`)")
            }
        }

        /** Migration 4→5: add browser_history table. */
        val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `browser_history` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `url` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `timestamp` INTEGER NOT NULL
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_history_url` ON `browser_history` (`url`)")
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_browser_history_timestamp` ON `browser_history` (`timestamp`)")
            }
        }

        /** Migration 5→6: add headersJson, pageUrl, mimeType columns to stream_history. */
        val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE `stream_history` ADD COLUMN `headersJson` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `stream_history` ADD COLUMN `pageUrl` TEXT DEFAULT NULL")
                db.execSQL("ALTER TABLE `stream_history` ADD COLUMN `mimeType` TEXT DEFAULT NULL")
            }
        }

        /** Migration 6→7: add play_history table ("recently played" media). */
        val MIGRATION_6_7 = object : Migration(6, 7) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("""
                    CREATE TABLE IF NOT EXISTS `play_history` (
                        `uri` TEXT NOT NULL,
                        `title` TEXT NOT NULL,
                        `isVideo` INTEGER NOT NULL,
                        `playedAt` INTEGER NOT NULL,
                        `artist` TEXT,
                        `mimeType` TEXT,
                        `thumbnailUri` TEXT,
                        PRIMARY KEY(`uri`)
                    )
                """.trimIndent())
                db.execSQL("CREATE INDEX IF NOT EXISTS `index_play_history_playedAt` ON `play_history` (`playedAt`)")
            }
        }
    }
}

package com.rhnxdev.hzplayer.data.datasource.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.PlaybackPositionDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.ServerConfigDao
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.StreamHistoryDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.PlaybackPositionEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.ServerConfigEntity
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.StreamHistoryEntity

@Database(
    entities = [
        MediaEntity::class,
        ServerConfigEntity::class,
        StreamHistoryEntity::class,
        PlaybackPositionEntity::class,
    ],
    version = 4,
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
    }
}

package com.rhnxdev.hzplayer.data.datasource.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
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
    version = 3,
    exportSchema = false,
)
abstract class HzPlayerDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
    abstract fun serverConfigDao(): ServerConfigDao
    abstract fun streamHistoryDao(): StreamHistoryDao
    abstract fun playbackPositionDao(): PlaybackPositionDao
}

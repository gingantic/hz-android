package com.rhnxdev.hzplayer.data.datasource.local.room

import androidx.room.Database
import androidx.room.RoomDatabase
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import com.rhnxdev.hzplayer.data.datasource.local.room.entities.MediaEntity

@Database(
    entities = [MediaEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class HzPlayerDatabase : RoomDatabase() {
    abstract fun mediaDao(): MediaDao
}

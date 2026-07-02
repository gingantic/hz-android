package com.rhnxdev.hzplayer.data.datasource.local.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "stream_history")
data class StreamHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val url: String,
    val title: String,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long,
)

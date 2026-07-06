package com.rhnxdev.hzplayer.data.datasource.local.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Saved playback position for "continue watching", keyed by the media URI/path.
 *
 * URI is the universal key: both the video library ([VideoItem.uri]) and the file
 * browser (raw file path) feed the same string into the player engine.
 */
@Entity(tableName = "playback_position")
data class PlaybackPositionEntity(
    @PrimaryKey val uri: String,
    val positionMs: Long,
    val durationMs: Long,
    val updatedAt: Long,
)

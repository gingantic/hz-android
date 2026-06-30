package com.rhnxdev.hzplayer.data.datasource.local.room.entities

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "media")
data class MediaEntity(
    @PrimaryKey
    val id: Long,
    val title: String,
    val uri: String,
    val mediaType: String, // "video", "audio", "folder", "file"
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    val mimeType: String? = null,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    val resolution: String? = null,
    val trackNumber: Int = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val isFavorite: Boolean = false,
    val watchedProgress: Float = 0f,
    val thumbnailUri: String? = null,
)

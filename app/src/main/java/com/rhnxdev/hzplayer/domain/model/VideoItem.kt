package com.rhnxdev.hzplayer.domain.model

data class VideoItem(
    val id: Long,
    val title: String,
    val uri: String,
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    val resolution: String? = null,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val mimeType: String? = null,
    val isFavorite: Boolean = false,
    val watchedProgress: Float = 0f,
    val thumbnailUri: String? = null,
)

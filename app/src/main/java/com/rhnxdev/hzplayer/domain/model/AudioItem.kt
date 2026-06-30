package com.rhnxdev.hzplayer.domain.model

data class AudioItem(
    val id: Long,
    val title: String,
    val uri: String,
    val artist: String? = null,
    val album: String? = null,
    val albumArtUri: String? = null,
    val durationMs: Long = 0,
    val trackNumber: Int = 0,
    val fileSize: Long = 0,
    val dateAdded: Long = 0,
    val mimeType: String? = null,
    val isFavorite: Boolean = false,
)

data class Album(
    val id: Long,
    val title: String,
    val artist: String? = null,
    val albumArtUri: String? = null,
    val trackCount: Int = 0,
    val year: Int? = null,
)

data class Artist(
    val id: Long,
    val name: String,
    val albumCount: Int = 0,
    val trackCount: Int = 0,
    val albumArtUri: String? = null,
)

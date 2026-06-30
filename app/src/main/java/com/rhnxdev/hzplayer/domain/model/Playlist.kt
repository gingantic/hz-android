package com.rhnxdev.hzplayer.domain.model

data class Playlist(
    val id: Long,
    val name: String,
    val description: String? = null,
    val mediaCount: Int = 0,
    val thumbnailUri: String? = null,
    val dateCreated: Long = 0,
    val dateModified: Long = 0,
)

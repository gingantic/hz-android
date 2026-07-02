package com.rhnxdev.hzplayer.domain.model

data class StreamHistoryItem(
    val id: Long = 0,
    val url: String,
    val title: String,
    val isFavorite: Boolean = false,
    val lastPlayedAt: Long = System.currentTimeMillis(),
)

package com.rhnxdev.hzplayer.domain.model

data class BrowserHistoryItem(
    val id: Long = 0,
    val url: String,
    val title: String,
    val timestamp: Long,
)

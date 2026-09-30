package com.rhnxdev.hzplayer.domain.model

/** A single "recently played" entry, newest plays first. Denormalised so the
 *  history list renders without re-querying the library. */
data class PlayHistoryItem(
    val uri: String,
    val title: String,
    val isVideo: Boolean,
    val playedAt: Long,
    val artist: String? = null,
    val mimeType: String? = null,
    val thumbnailUri: String? = null,
)

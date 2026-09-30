package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.PlayHistoryItem
import kotlinx.coroutines.flow.Flow

/** Persists a "recently played" list of media (any source), deduped by URI. */
interface PlayHistoryRepository {

    /** History entries, most recently played first. */
    fun observeHistory(): Flow<List<PlayHistoryItem>>

    /** Record (or bump) a play at the current time. Ignores blank uri. */
    suspend fun recordPlay(
        uri: String,
        title: String,
        isVideo: Boolean,
        artist: String? = null,
        mimeType: String? = null,
        thumbnailUri: String? = null,
    )

    /** Remove a single entry. */
    suspend fun remove(uri: String)

    /** Clear all history. */
    suspend fun clear()
}

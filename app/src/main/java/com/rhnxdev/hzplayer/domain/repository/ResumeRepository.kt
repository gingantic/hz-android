package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.PlaybackProgress

/** Persists playback position per media URI so playback can resume ("continue watching"). */
interface ResumeRepository {

    /**
     * Save progress for [uri]. Ignores blank uri / non-positive values. If [positionMs] is within
     * the last few seconds of [durationMs] the item is treated as finished and any saved row cleared.
     */
    suspend fun saveProgress(uri: String, positionMs: Long, durationMs: Long)

    /** Position to resume from, or 0 when there's nothing worth resuming (near start/end / none). */
    suspend fun getResumePosition(uri: String): Long

    /** Get playback progress details for a batch of URIs. */
    suspend fun getPlaybackProgressList(uris: List<String>): Map<String, PlaybackProgress>
}

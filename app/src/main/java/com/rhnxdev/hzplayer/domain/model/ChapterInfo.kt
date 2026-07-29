package com.rhnxdev.hzplayer.domain.model

/**
 * A chapter marker embedded in a media container (MKV chapters, MP4 chpl,
 * OGG chapters, …), probed via the native FFmpeg demuxer.
 */
data class ChapterInfo(
    val startMs: Long,
    val endMs: Long,
    val title: String,
)

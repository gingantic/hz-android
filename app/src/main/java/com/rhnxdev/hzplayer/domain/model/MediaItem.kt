package com.rhnxdev.hzplayer.domain.model

enum class MediaType {
    VIDEO,
    AUDIO,
    FOLDER,
    FILE,
}

enum class SortType {
    TITLE,
    DATE_ADDED,
    DATE_MODIFIED,
    DURATION,
    FILE_SIZE,
    ARTIST,
    ALBUM,
}

enum class ViewMode {
    GRID,
    LIST,
}

enum class RepeatMode {
    NONE,
    ALL,
    ONE,
}

data class MediaItem(
    val id: Long,
    val title: String,
    val uri: String,
    val type: MediaType,
    val durationMs: Long = 0,
    val fileSize: Long = 0,
    val dateAdded: Long = 0,
    val dateModified: Long = 0,
    val mimeType: String? = null,
    val isFavorite: Boolean = false,
    val thumbnailUri: String? = null,
)

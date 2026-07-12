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

enum class SortDirection {
    ASCENDING,
    DESCENDING,
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

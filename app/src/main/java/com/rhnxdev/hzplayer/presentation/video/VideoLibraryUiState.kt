package com.rhnxdev.hzplayer.presentation.video

import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.model.VideoItem

data class VideoLibraryUiState(
    val categories: List<VideoCategory> = emptyList(),
    val recentVideos: List<VideoItem> = emptyList(),
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val isEmpty: Boolean = false,
)

data class VideoCategory(
    val title: String,
    val videos: List<VideoItem>,
)

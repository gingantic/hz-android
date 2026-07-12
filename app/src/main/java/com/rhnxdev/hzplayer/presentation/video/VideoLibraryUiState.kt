package com.rhnxdev.hzplayer.presentation.video

import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.model.VideoItem

import androidx.compose.runtime.Immutable

@Immutable
data class VideoLibraryUiState(
    val categories: List<VideoCategory> = emptyList(),
    val recentVideos: List<VideoItem> = emptyList(),
    val allVideos: List<VideoItem> = emptyList(),
    val filteredVideos: List<VideoItem> = emptyList(),
    val isLoading: Boolean = true,
    val error: String? = null,
    val viewMode: ViewMode = ViewMode.GRID,
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val isEmpty: Boolean = false,
)

@Immutable
data class VideoCategory(
    val title: String,
    val videos: List<VideoItem>,
    val isRecent: Boolean = false,
)

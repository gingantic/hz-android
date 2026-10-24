package com.rhnxdev.hzplayer.presentation.video

import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ThumbnailSize
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
    val thumbnailSize: ThumbnailSize = ThumbnailSize.LARGE,
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val isEmpty: Boolean = false,
    val selectedFolder: String? = null, // null = folder list root; RECENT_KEY = Recent pseudo-folder
    val deleteConfirmVideo: VideoItem? = null,
    /**
     * Video inside its undo grace period: hidden from the library, not yet removed
     * from disk. An undo snackbar is shown while non-null.
     */
    val deleteGraceVideo: VideoItem? = null,
    val showAllFilesAccessPrompt: Boolean = false,
    val deleteOutcome: DeleteOutcome? = null,
)

/** One-shot result of a delete, shown once as a snackbar and then cleared. */
sealed interface DeleteOutcome {
    data class Deleted(val title: String) : DeleteOutcome
    data object Failed : DeleteOutcome
}

@Immutable
data class VideoCategory(
    val title: String,
    val videos: List<VideoItem>,
    val isRecent: Boolean = false,
)

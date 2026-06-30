package com.rhnxdev.hzplayer.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoLibraryUiState())
    val uiState: StateFlow<VideoLibraryUiState> = _uiState.asStateFlow()

    private val previewVideos: List<VideoItem> = PreviewMedia.videoMovies.mapIndexed { index, item ->
        VideoItem(
            id = index.toLong(),
            title = item["title"] as? String ?: "",
            uri = "",
            durationMs = (item["durationMs"] as? Long) ?: 0,
            resolution = item["resolution"] as? String,
            dateAdded = System.currentTimeMillis() - (index * 86_400_000L),
        )
    }

    private val previewRecent: List<VideoItem> = PreviewMedia.recentVideos.mapIndexed { index, item ->
        VideoItem(
            id = (100 + index).toLong(),
            title = item["title"] as? String ?: "",
            uri = "",
            durationMs = (item["durationMs"] as? Long) ?: 0,
            watchedProgress = ((item["progress"] as? Double)?.toFloat()) ?: 0f,
            dateAdded = System.currentTimeMillis() - (index * 3_600_000L),
        )
    }

    init {
        loadVideos()
        observePreferences()
    }

    private fun loadVideos() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            // Preview data path — will be replaced with repository calls
            try {
                val categories = listOf(
                    VideoCategory(title = "Recent", videos = previewRecent),
                    VideoCategory(title = "All Videos", videos = previewVideos),
                )

                _uiState.update {
                    it.copy(
                        categories = categories,
                        recentVideos = previewRecent,
                        allVideos = previewVideos,
                        filteredVideos = previewVideos,
                        isLoading = false,
                        isEmpty = previewVideos.isEmpty(),
                    )
                }

                // Future: replace with real repository
                // mediaRepository.getAllVideos(sortType)
                //     .catch { e -> _uiState.update { it.copy(error = e.message, isLoading = false) } }
                //     .collect { videos -> updateVideoList(videos) }

            } catch (e: Exception) {
                _uiState.update {
                    it.copy(
                        error = e.message ?: "Failed to load videos",
                        isLoading = false,
                    )
                }
            }
        }
    }

    private fun observePreferences() {
        viewModelScope.launch {
            userPreferencesRepository.getViewMode("video_library").collect { mode ->
                _uiState.update { it.copy(viewMode = mode) }
            }
        }
        viewModelScope.launch {
            userPreferencesRepository.getSortType("video_library").collect { sort ->
                _uiState.update { it.copy(sortType = sort) }
                applySort(sort)
            }
        }
    }

    fun onViewModeChanged(mode: ViewMode) {
        viewModelScope.launch {
            userPreferencesRepository.setViewMode("video_library", mode)
        }
    }

    fun onSortChanged(sort: SortType) {
        viewModelScope.launch {
            userPreferencesRepository.setSortType("video_library", sort)
        }
    }

    fun onSearchToggle() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                isSearchActive = true,
            )
        }
        applyFilter("")
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                isSearchActive = true,
            )
        }
        applyFilter(query)
    }

    fun onClearSearch() {
        _uiState.update {
            it.copy(
                searchQuery = null,
                isSearchActive = false,
                filteredVideos = it.allVideos,
            )
        }
    }

    fun onRetry() {
        loadVideos()
    }

    fun onVideoClicked(video: VideoItem) {
        // Will navigate to player when implemented
    }

    private fun applySort(sort: SortType) {
        val sorted = when (sort) {
            SortType.TITLE -> _uiState.value.allVideos.sortedBy { it.title }
            SortType.DATE_ADDED -> _uiState.value.allVideos.sortedByDescending { it.dateAdded }
            SortType.DURATION -> _uiState.value.allVideos.sortedByDescending { it.durationMs }
            SortType.DATE_MODIFIED -> _uiState.value.allVideos.sortedByDescending { it.dateModified }
            SortType.FILE_SIZE -> _uiState.value.allVideos.sortedByDescending { it.fileSize }
            else -> _uiState.value.allVideos.sortedBy { it.title }
        }
        _uiState.update {
            it.copy(
                allVideos = sorted,
                filteredVideos = if (it.isSearchActive) it.filteredVideos else sorted,
            )
        }
    }

    private fun applyFilter(query: String) {
        val filtered = _uiState.value.allVideos.filter {
            it.title.contains(query, ignoreCase = true)
        }
        _uiState.update { it.copy(filteredVideos = filtered) }
    }
}

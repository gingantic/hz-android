package com.rhnxdev.hzplayer.presentation.video

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.BuildConfig
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class VideoLibraryViewModel @Inject constructor(
    private val mediaRepository: MediaRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(VideoLibraryUiState())
    val uiState: StateFlow<VideoLibraryUiState> = _uiState.asStateFlow()

    val search = SearchDelegate()

    companion object {
        /** Cached preview lists — avoid re-allocation per VM init. */
        private val PREVIEW_VIDEOS: List<VideoItem> = PreviewMedia.videoMovies.mapIndexed { index, item ->
            VideoItem(
                id = index.toLong(),
                title = item["title"] as? String ?: "",
                uri = item["uri"] as? String ?: "",
                durationMs = (item["durationMs"] as? Long) ?: 0,
                resolution = item["resolution"] as? String,
                dateAdded = System.currentTimeMillis() - (index * 86_400_000L),
            )
        }

        private val PREVIEW_RECENT: List<VideoItem> = PreviewMedia.recentVideos.mapIndexed { index, item ->
            VideoItem(
                id = (100 + index).toLong(),
                title = item["title"] as? String ?: "",
                uri = item["uri"] as? String ?: "",
                durationMs = (item["durationMs"] as? Long) ?: 0,
                watchedProgress = ((item["progress"] as? Double)?.toFloat()) ?: 0f,
                dateAdded = System.currentTimeMillis() - (index * 3_600_000L),
            )
        }
    }

    /** Fallback preview data used when MediaStore has no results. — reused companion cache */
    

    init {
        loadVideos()
        observePreferences()
    }

    private fun loadVideos(forceRefresh: Boolean = false) {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, error = null) }

            try {
                // Load initial sort type
                val sortType = userPreferencesRepository.getSortType("video_library").first()

                // Try real data from MediaStore via repository
                mediaRepository.getAllVideos(sortType, forceRefresh)
                    .catch { e -> emitFailure(e.message) }
                    .collect { videos -> emitLoaded(videos) }
            } catch (e: Exception) {
                emitFailure(e.message)
            }
        }
    }

    /** Real data arrived (or an explicitly empty result). */
    private fun emitLoaded(videos: List<VideoItem>) {
        if (videos.isNotEmpty()) {
            val cutoff = System.currentTimeMillis() - (7 * 86_400_000L) // 7 days
            val recent = videos.filter { it.dateAdded >= cutoff }
            val categories = groupVideosIntoCategories(videos, recent)
            _uiState.update {
                it.copy(
                    categories = categories,
                    recentVideos = recent,
                    allVideos = videos,
                    filteredVideos = videos,
                    isLoading = false,
                    isEmpty = false,
                )
            }
        } else {
            // Empty result. Only show fictional preview data in debug
            // builds; in release, surface an empty library instead.
            if (BuildConfig.DEBUG) {
                val categories = groupVideosIntoCategories(PREVIEW_VIDEOS)
                _uiState.update {
                    it.copy(
                        categories = categories,
                        allVideos = PREVIEW_VIDEOS,
                        filteredVideos = PREVIEW_VIDEOS,
                        isLoading = false,
                        isEmpty = PREVIEW_VIDEOS.isEmpty(),
                    )
                }
            } else {
                _uiState.update {
                    it.copy(
                        categories = emptyList(),
                        allVideos = emptyList(),
                        filteredVideos = emptyList(),
                        isLoading = false,
                        isEmpty = true,
                    )
                }
            }
        }
    }

    /** Load failed — fall back to preview data only in debug; real error in release. */
    private fun emitFailure(message: String?) {
        if (BuildConfig.DEBUG) {
            val categories = groupVideosIntoCategories(PREVIEW_VIDEOS, PREVIEW_RECENT)
            _uiState.update {
                it.copy(
                    categories = categories,
                    recentVideos = PREVIEW_RECENT,
                    allVideos = PREVIEW_VIDEOS,
                    filteredVideos = PREVIEW_VIDEOS,
                    isLoading = false,
                    error = if (PREVIEW_VIDEOS.isEmpty()) message else null,
                )
            }
        } else {
            _uiState.update {
                it.copy(
                    categories = emptyList(),
                    recentVideos = emptyList(),
                    allVideos = emptyList(),
                    filteredVideos = emptyList(),
                    isLoading = false,
                    error = message,
                )
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
        search.toggle()
        applyFilter("")
    }

    fun onSearchQueryChanged(query: String) {
        search.queryChanged(query)
        applyFilter(query)
    }

    fun onClearSearch() {
        search.clear()
        _uiState.update { it.copy(filteredVideos = it.allVideos) }
    }

    fun onRetry() {
        loadVideos()
    }

    fun onRefresh() {
        if (_uiState.value.isLoading) return
        loadVideos(forceRefresh = true)
    }

    /** Called when this tab regains focus — refresh from source without blocking the cached view. */
    fun onTabFocused() {
        loadVideos(forceRefresh = true)
    }

    fun onVideoClicked(video: VideoItem) {
        playerRepository.playVideo(video)
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
        val categories = groupVideosIntoCategories(sorted, _uiState.value.recentVideos)
        _uiState.update {
            it.copy(
                allVideos = sorted,
                categories = categories,
                filteredVideos = if (search.isSearchActive.value) it.filteredVideos else sorted,
            )
        }
    }

    private fun applyFilter(query: String) {
        val filtered = _uiState.value.allVideos.filter {
            it.title.contains(query, ignoreCase = true)
        }
        _uiState.update { it.copy(filteredVideos = filtered) }
    }

    private fun getParentFolderName(uri: String): String {
        try {
            val cleanPath = uri.removePrefix("file://")
            if (cleanPath.startsWith("/")) {
                val file = java.io.File(cleanPath)
                val parentName = file.parentFile?.name
                if (!parentName.isNullOrEmpty() && parentName != "0") {
                    return parentName
                }
            } else if (cleanPath.startsWith("http://") || cleanPath.startsWith("https://")) {
                val urlPath = android.net.Uri.parse(cleanPath).path
                if (!urlPath.isNullOrEmpty()) {
                    val file = java.io.File(urlPath)
                    val parentName = file.parentFile?.name
                    if (!parentName.isNullOrEmpty() && parentName != "sample") {
                        return parentName
                    }
                }
            }
        } catch (e: Exception) {
            // ignore
        }
        return "Internal Storage"
    }

    private fun groupVideosIntoCategories(videos: List<VideoItem>, recentVideos: List<VideoItem> = emptyList()): List<VideoCategory> {
        if (videos.isEmpty()) return emptyList()
        val categoriesList = mutableListOf<VideoCategory>()
        
        if (recentVideos.isNotEmpty()) {
            categoriesList.add(VideoCategory(title = "Recent", videos = recentVideos.take(10), isRecent = true))
        }
        
        val grouped = videos.groupBy { getParentFolderName(it.uri) }
        val folderCategories = grouped.map { (folderName, folderVideos) ->
            VideoCategory(title = folderName, videos = folderVideos)
        }.sortedBy { it.title.lowercase() }
        
        categoriesList.addAll(folderCategories)
        return categoriesList
    }
}

package com.rhnxdev.hzplayer.presentation.browse

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.util.DirectoryLruCache
import com.rhnxdev.hzplayer.core.util.buildBreadcrumbs
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val userPrefs: UserPreferencesRepository,
    private val resumeRepository: ResumeRepository,
    private val mediaRepository: MediaRepository,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val cache = DirectoryLruCache<FolderItem>()
    private var showHidden = false

    private val sortKey = "file_browser"

    val search = SearchDelegate()

    private fun resolveFavorites(): List<FavoriteShortcut> {
        val ext = Environment.getExternalStorageDirectory()
        return listOfNotNull(
            FavoriteShortcut("Downloads", "${ext.absolutePath}/Download", HzPlayerIcons.Download),
            FavoriteShortcut("Movies", "${ext.absolutePath}/Movies", HzPlayerIcons.VideoLibrary),
            FavoriteShortcut("Music", "${ext.absolutePath}/Music", HzPlayerIcons.AudioBrowser),
        ).filter { File(it.path).exists() }.map { fav ->
            val count = File(fav.path).listFiles()?.size ?: 0
            fav.copy(itemCount = count)
        }
    }

    init {
        _uiState.update { it.copy(favorites = resolveFavorites()) }
        loadRoots()
        viewModelScope.launch {
            val savedSort = userPrefs.getSortType(sortKey).first()
            val savedMediaMode = userPrefs.fileBrowserMediaMode.first()
            _uiState.update { it.copy(sortType = savedSort, isMediaMode = savedMediaMode) }
        }
        viewModelScope.launch {
            userPrefs.showHiddenFiles.collect { hidden ->
                showHidden = hidden
                val layers = _uiState.value.layers
                if (layers.isNotEmpty()) {
                    val path = layers.last().path
                    if (path.isNotEmpty()) {
                        cache.remove(path)
                        val lastIdx = layers.size - 1
                        updateLayer(lastIdx) { it.copy(isLoading = true) }
                        // Re-load the topmost layer with new hidden setting
                        loadDirectory(path, lastIdx)
                    }
                }
            }
        }
    }

    fun onRefresh() {
        val state = _uiState.value
        when {
            state.mode == FileBrowserMode.ROOTS -> {
                if (state.isLoading) return
                cache.clear()
                loadRoots()
            }
            state.layers.isNotEmpty() -> {
                val lastIdx = state.layers.lastIndex
                if (state.layers[lastIdx].isLoading) return
                val path = state.layers[lastIdx].path
                if (path.isNotEmpty()) {
                    cache.remove(path)
                    updateLayer(lastIdx) { it.copy(isLoading = true) }
                    loadDirectory(path, lastIdx)
                }
            }
        }
    }

    private fun loadRoots() {
        search.clear()
        _uiState.update {
            it.copy(
                mode = FileBrowserMode.ROOTS,
                roots = emptyList(),
                layers = emptyList(),
                isLoading = true,
                error = null,
            )
        }
        viewModelScope.launch {
            val minDelayJob = launch { kotlinx.coroutines.delay(300) }
            fileRepository.getStorageRoots()
                .catch { emit(emptyList()) }
                .collect { roots ->
                    minDelayJob.join()
                    _uiState.update {
                        it.copy(roots = roots, isLoading = false)
                    }
                }
        }
    }

    fun onStorageRootClicked(root: FolderItem) {
        _uiState.update { it.copy(layers = emptyList()) }
        pushLayer(root.path)
    }

    fun onFavoriteClicked(shortcut: FavoriteShortcut) {
        _uiState.update { it.copy(layers = emptyList()) }
        pushLayer(shortcut.path)
    }

    fun collectVideoPlaylist(): List<VideoItem> {
        val currentLayer = _uiState.value.layers.lastOrNull() ?: return emptyList()
        var idCounter = 0L
        return currentLayer.items
            .filter { !it.isDirectory && (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name)) }
            .map { item ->
                idCounter++
                VideoItem(
                    id = item.id,
                    title = item.name.substringBeforeLast('.'),
                    uri = item.path,
                    durationMs = item.durationMs,
                    fileSize = item.fileSize,
                    mimeType = item.mimeType,
                )
            }
    }

    fun onFolderClicked(item: FolderItem) {
        if (item.isDirectory) pushLayer(item.path)
    }

    fun onBreadcrumbClicked(path: String) {
        val layers = _uiState.value.layers
        val idx = layers.indexOfFirst { it.path == path }
        if (idx >= 0) {
            // Pop layers down to (and including) this one
            _uiState.update { it.copy(layers = layers.take(idx + 1)) }
        } else {
            // Path not in current layers — replace all
            _uiState.update { it.copy(layers = emptyList()) }
            pushLayer(path)
        }
    }

    fun onNavigateUp(): Boolean {
        val layers = _uiState.value.layers
        return if (layers.size > 1) {
            _uiState.update { it.copy(layers = layers.dropLast(1)) }
            true
        } else {
            loadRoots()
            true
        }
    }

    fun onBackToRoots() {
        cache.clear()
        _uiState.update { it.copy(layers = emptyList()) }
        loadRoots()
    }

    fun onToggleMediaMode() {
        val enabled = !_uiState.value.isMediaMode
        _uiState.update { it.copy(isMediaMode = enabled) }
        viewModelScope.launch { userPrefs.setFileBrowserMediaMode(enabled) }
    }

    fun onSearchToggle() = search.toggle()
    fun onSearchQueryChanged(query: String) = search.queryChanged(query)
    fun onClearSearch() = search.clear()

    fun onSortChanged(sort: SortType) {
        _uiState.update { it.copy(sortType = sort) }
        viewModelScope.launch {
            userPrefs.setSortType(sortKey, sort)
        }
        // Re-apply sort to current visible layers
        reapplySort()
    }

    fun onRetry() {
        val state = _uiState.value
        if (state.mode == FileBrowserMode.ROOTS) loadRoots()
        else if (state.layers.isNotEmpty()) {
            val idx = state.layers.lastIndex
            updateLayer(idx) { it.copy(isLoading = true) }
            loadDirectory(state.layers[idx].path, idx)
        }
    }

    private fun pushLayer(path: String) {
        val layer = DirectoryLayer(
            path = path,
            breadcrumbs = buildBreadcrumbs(path),
            isLoading = true,
        )
        _uiState.update {
            it.copy(
                mode = FileBrowserMode.BROWSING,
                layers = it.layers + layer,
            )
        }
        loadDirectory(path, _uiState.value.layers.lastIndex)
    }

    private suspend fun enrichItemsWithPlaybackMetadata(items: List<FolderItem>): List<FolderItem> {
        val fileItems = items.filter { !it.isDirectory }
        if (fileItems.isEmpty()) return items

        val paths = fileItems.map { it.path }
        val progressMap = resumeRepository.getPlaybackProgressList(paths)
        val localVideos = mediaRepository.getVideosByUris(paths)
        val durationMap = localVideos.associate { it.uri to it.durationMs }
        val resolutionMap = localVideos.associate { it.uri to it.resolution }
        val dateAddedMap = localVideos.associate { it.uri to it.dateAdded }

        return items.map { item ->
            if (item.isDirectory) {
                item
            } else {
                val progress = progressMap[item.path]
                val duration = progress?.durationMs ?: durationMap[item.path] ?: 0L
                val position = progress?.positionMs ?: 0L
                item.copy(
                    durationMs = duration,
                    playbackPositionMs = position,
                    resolution = resolutionMap[item.path],
                    dateAdded = dateAddedMap[item.path] ?: 0L
                )
            }
        }
    }

    private fun loadDirectory(path: String, layerIndex: Int) {
        viewModelScope.launch {
            search.clear()

            val cached = cache.get(path)
            if (cached != null) {
                val enriched = enrichItemsWithPlaybackMetadata(cached)
                val sorted = sortItems(enriched, _uiState.value.sortType)
                updateLayer(layerIndex) {
                    it.copy(items = sorted, isEmpty = cached.isEmpty(), error = null, isLoading = false)
                }
                return@launch
            }

            try {
                val minDelayJob = launch { kotlinx.coroutines.delay(300) }
                fileRepository.listDirectory(path, showHidden).collect { items ->
                    cache.put(path, items)
                    val enriched = enrichItemsWithPlaybackMetadata(items)
                    val sorted = sortItems(enriched, _uiState.value.sortType)
                    minDelayJob.join()
                    updateLayer(layerIndex) {
                        it.copy(items = sorted, isEmpty = items.isEmpty(), error = null, isLoading = false)
                    }
                }
            } catch (e: Exception) {
                updateLayer(layerIndex) {
                    it.copy(error = e.message ?: "Failed", isLoading = false)
                }
            }
        }
    }

    private fun updateLayer(index: Int, transform: (DirectoryLayer) -> DirectoryLayer) {
        _uiState.update { state ->
            val layers = state.layers.toMutableList()
            if (index in layers.indices) {
                layers[index] = transform(layers[index])
            }
            state.copy(layers = layers)
        }
    }

    private fun reapplySort() {
        val state = _uiState.value
        val sortedLayers = state.layers.map { layer ->
            layer.copy(items = sortItems(layer.items, state.sortType))
        }
        _uiState.update { it.copy(layers = sortedLayers) }
    }

    private fun sortItems(items: List<FolderItem>, sort: SortType): List<FolderItem> {
        val (dirs, files) = items.partition { it.isDirectory }
        val sortedDirs = when (sort) {
            SortType.TITLE -> dirs.sortedBy { it.name.lowercase() }
            SortType.DATE_MODIFIED -> dirs.sortedByDescending { it.dateModified }
            SortType.FILE_SIZE -> dirs.sortedByDescending { it.fileSize }
            else -> dirs.sortedBy { it.name.lowercase() }
        }
        val sortedFiles = when (sort) {
            SortType.TITLE -> files.sortedBy { it.name.lowercase() }
            SortType.DATE_MODIFIED -> files.sortedByDescending { it.dateModified }
            SortType.FILE_SIZE -> files.sortedByDescending { it.fileSize }
            else -> files.sortedBy { it.name.lowercase() }
        }
        return sortedDirs + sortedFiles
    }
}

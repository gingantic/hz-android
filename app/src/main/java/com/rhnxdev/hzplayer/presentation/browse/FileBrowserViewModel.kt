package com.rhnxdev.hzplayer.presentation.browse

import android.content.res.Configuration
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.util.ArchiveBrowsePath
import com.rhnxdev.hzplayer.core.util.ArchiveUri
import com.rhnxdev.hzplayer.core.util.DirectoryLruCache
import com.rhnxdev.hzplayer.core.util.buildArchiveBreadcrumbs
import com.rhnxdev.hzplayer.core.util.isArchiveExtension
import com.rhnxdev.hzplayer.core.util.isSolidArchiveExtension
import com.rhnxdev.hzplayer.core.util.sortFilesByType
import com.rhnxdev.hzplayer.core.util.buildBreadcrumbs
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.ArchiveEntry
import com.rhnxdev.hzplayer.domain.repository.ArchiveRepository
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.domain.repository.MediaRepository
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Persisted scroll position for a browsed directory.
 *
 * @param index        first visible item index — stable across orientation changes.
 * @param offset       pixel offset of that item — only meaningful in the same orientation.
 * @param orientation  orientation when saved; lets restore decide between an exact
 *                     (same orientation) or top-aligned (after rotation) restore.
 * @param isAtEnd      true when the last item of the list was visible; restored by
 *                     scrolling to the absolute end so LazyColumn's end-clamping places
 *                     it correctly instead of bouncing back ~N items.
 */
data class SavedScrollPosition(
    val index: Int,
    val offset: Int,
    val orientation: Int,
    val isAtEnd: Boolean = false,
)

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val userPrefs: UserPreferencesRepository,
    private val resumeRepository: ResumeRepository,
    private val mediaRepository: MediaRepository,
    private val archiveRepository: ArchiveRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val cache = DirectoryLruCache<FolderItem>()
    private var showHidden = false
    private var showSolidArchiveWarning = true
    private val archivePasswords = mutableMapOf<String, String>()

    private val sortKey = "file_browser"

    val search = SearchDelegate()

    private fun resolveFavorites(quickAccessPaths: Set<String>): List<FavoriteShortcut> {
        val ext = Environment.getExternalStorageDirectory()
        return quickAccessPaths.mapNotNull { path ->
            val file = File(path)
            if (!file.exists()) return@mapNotNull null
            val name = when (path) {
                "${ext.absolutePath}/Download" -> "Downloads"
                "${ext.absolutePath}/Movies" -> "Movies"
                "${ext.absolutePath}/Music" -> "Music"
                else -> file.name.ifBlank { path }
            }
            val icon = when {
                path == "${ext.absolutePath}/Download" || name.equals("Downloads", ignoreCase = true) -> HzPlayerIcons.Download
                path == "${ext.absolutePath}/Movies" || name.equals("Movies", ignoreCase = true) -> HzPlayerIcons.VideoLibrary
                path == "${ext.absolutePath}/Music" || name.equals("Music", ignoreCase = true) -> HzPlayerIcons.AudioBrowser
                else -> Icons.Filled.Folder
            }
            val count = file.listFiles()?.size ?: 0
            FavoriteShortcut(name, path, icon, count)
        }
    }

    init {
        loadRoots()
        viewModelScope.launch {
            userPrefs.quickAccessFolders.collect { paths ->
                _uiState.update {
                    it.copy(
                        favorites = resolveFavorites(paths),
                        quickAccessPaths = paths,
                    )
                }
            }
        }
        viewModelScope.launch {
            val savedSort = userPrefs.getSortType(sortKey).first()
            val savedDir = userPrefs.getSortDirection(sortKey).first()
            val savedMediaMode = userPrefs.fileBrowserMediaMode.first()
            _uiState.update { it.copy(sortType = savedSort, sortDirection = savedDir, isMediaMode = savedMediaMode) }
        }
        viewModelScope.launch {
            userPrefs.archivePasswords.collect { persisted ->
                archivePasswords.clear()
                archivePasswords.putAll(persisted)
            }
        }
        viewModelScope.launch {
            userPrefs.showSolidArchiveWarning.collect { show ->
                showSolidArchiveWarning = show
            }
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
            fileRepository.getStorageRoots()
                .catch { emit(emptyList()) }
                .collect { roots ->
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

    fun onToggleQuickAccess(path: String) {
        viewModelScope.launch {
            userPrefs.toggleQuickAccessFolder(path)
        }
    }

    fun collectVideoPlaylist(): List<VideoItem> {
        val currentLayer = _uiState.value.layers.lastOrNull() ?: return emptyList()
        return currentLayer.items.toVideoPlaylist()
    }

    /**
     * Collect the videos directly inside [folder] (without navigating into it) and
     * deliver them sorted with the current sort settings as a playable playlist.
     * Supports both real directories and virtual archive folders.
     */
    fun collectFolderVideoPlaylist(folder: FolderItem, onReady: (List<VideoItem>) -> Unit) {
        if (!folder.isDirectory) return
        viewModelScope.launch {
            val children = cache.get(folder.path) ?: try {
                if (ArchiveBrowsePath.isArchiveBrowsePath(folder.path)) {
                    val (container, prefix) = ArchiveBrowsePath.parse(folder.path)
                    archiveRepository.listEntries(container, archivePasswords[container])
                        .getOrNull()
                        ?.let { archiveChildren(container, prefix, it) }
                        ?: emptyList()
                } else {
                    fileRepository.listDirectory(folder.path, showHidden).first()
                }
            } catch (e: Exception) {
                emptyList()
            }
            // Fresh listings carry no durations — enrich like loadDirectory does so
            // the playlist drawer can show them (cached items are already enriched;
            // re-enriching is harmless and refreshes stale progress).
            val enriched = enrichItemsWithPlaybackMetadata(children)
            val sorted = sortFilesByType(
                enriched,
                _uiState.value.sortType,
                isDirectory = { it.isDirectory },
                name = { it.name },
                dateModified = { it.dateModified },
                size = { it.fileSize },
                descending = _uiState.value.sortDirection == SortDirection.DESCENDING,
            )
            onReady(sorted.toVideoPlaylist())
        }
    }

    private fun List<FolderItem>.toVideoPlaylist(): List<VideoItem> =
        filter { !it.isDirectory && (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name)) }
            .map { item ->
                VideoItem(
                    id = item.id,
                    title = item.name.substringBeforeLast('.'),
                    uri = item.path,
                    durationMs = item.durationMs,
                    fileSize = item.fileSize,
                    mimeType = item.mimeType,
                )
            }

    fun onFolderClicked(item: FolderItem) {
        if (item.isDirectory) pushLayer(item.path)
    }

    /** Enter an archive container as a virtual browsing layer (in-place, no extraction). */
    fun onOpenArchive(item: FolderItem) {
        if (isSolidArchiveExtension(item.name) && showSolidArchiveWarning) {
            _uiState.update { it.copy(solidArchiveWarningContainer = item) }
        } else {
            pushLayer(ArchiveBrowsePath.build(item.path, ""))
        }
    }

    fun onConfirmSolidArchiveWarning(dontShowAgain: Boolean) {
        val item = _uiState.value.solidArchiveWarningContainer
        _uiState.update { it.copy(solidArchiveWarningContainer = null) }
        if (dontShowAgain) {
            viewModelScope.launch { userPrefs.setShowSolidArchiveWarning(false) }
        }
        if (item != null) {
            pushLayer(ArchiveBrowsePath.build(item.path, ""))
        }
    }

    fun onDismissSolidArchiveWarning() {
        _uiState.update { it.copy(solidArchiveWarningContainer = null) }
    }

    fun onCancelPasswordPrompt() {
        _uiState.update { it.copy(passwordPromptContainer = null, passwordError = null) }
        onNavigateUp()
    }

    fun onProvidePassword(password: String) {
        val container = _uiState.value.passwordPromptContainer ?: return
        _uiState.update { it.copy(passwordPromptContainer = null, passwordError = null) }
        archivePasswords[container] = password
        viewModelScope.launch { userPrefs.setArchivePassword(container, password) }

        val layers = _uiState.value.layers
        val idx = layers.indexOfLast {
            if (ArchiveBrowsePath.isArchiveBrowsePath(it.path)) {
                val (c, _) = ArchiveBrowsePath.parse(it.path)
                c == container
            } else false
        }
        if (idx >= 0) {
            updateLayer(idx) { it.copy(isLoading = true, error = null) }
            loadArchiveDirectory(layers[idx].path, idx)
        }
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

    fun onSortChanged(sort: SortType, direction: SortDirection = SortDirection.ASCENDING) {
        _uiState.update { it.copy(sortType = sort, sortDirection = direction) }
        viewModelScope.launch {
            userPrefs.setSortType(sortKey, sort)
            userPrefs.setSortDirection(sortKey, direction)
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
        val breadcrumbs = if (ArchiveBrowsePath.isArchiveBrowsePath(path)) {
            val (container, prefix) = ArchiveBrowsePath.parse(path)
            buildArchiveBreadcrumbs(container, prefix)
        } else {
            buildBreadcrumbs(path)
        }
        val layer = DirectoryLayer(
            path = path,
            breadcrumbs = breadcrumbs,
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

    private suspend fun enrichItemsWithPlaybackMetadata(items: List<FolderItem>): List<FolderItem> = coroutineScope {
        val fileItems = items.filter { !it.isDirectory }
        if (fileItems.isEmpty()) return@coroutineScope items

        val paths = fileItems.map { it.path }
        val showProgressDeferred = async { userPrefs.showWatchProgress.first() }
        val progressMapDeferred = async { resumeRepository.getPlaybackProgressList(paths) }
        val localVideosDeferred = async { mediaRepository.getVideosByUris(paths) }

        val showProgress = showProgressDeferred.await()
        val progressMap = progressMapDeferred.await()
        val localVideos = localVideosDeferred.await()

        val durationMap = localVideos.associate { it.uri to it.durationMs }
        val resolutionMap = localVideos.associate { it.uri to it.resolution }
        val dateAddedMap = localVideos.associate { it.uri to it.dateAdded }

        items.map { item ->
            if (item.isDirectory) {
                item
            } else {
                val progress = progressMap[item.path]
                val duration = progress?.durationMs ?: durationMap[item.path] ?: 0L
                val position = if (showProgress) progress?.positionMs ?: 0L else 0L
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
        if (ArchiveBrowsePath.isArchiveBrowsePath(path)) {
            loadArchiveDirectory(path, layerIndex)
            return
        }
        viewModelScope.launch {
            search.clear()

            val cached = cache.get(path)
            if (cached != null) {
                updateLayer(layerIndex) {
                    it.copy(items = cached, isEmpty = cached.isEmpty(), error = null, isLoading = false)
                }
                return@launch
            }

            try {
                fileRepository.listDirectory(path, showHidden).collect { items ->
                    val enriched = enrichItemsWithPlaybackMetadata(items)
                    val sorted = sortFilesByType(
                        enriched,
                        _uiState.value.sortType,
                        isDirectory = { it.isDirectory },
                        name = { it.name },
                        dateModified = { it.dateModified },
                        size = { it.fileSize },
                    )
                    cache.put(path, sorted)
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

    /** List one level inside an archive, mapping entries to navigable [FolderItem]s. */
    private fun loadArchiveDirectory(path: String, layerIndex: Int) {
        viewModelScope.launch {
            search.clear()

            cache.get(path)?.let { cached ->
                updateLayer(layerIndex) {
                    it.copy(items = sortArchive(cached), isEmpty = cached.isEmpty(), error = null, isLoading = false)
                }
                return@launch
            }

            val (container, prefix) = ArchiveBrowsePath.parse(path)
            val savedPassword = archivePasswords[container]
            archiveRepository.listEntries(container, savedPassword).fold(
                onSuccess = { entries ->
                    val children = archiveChildren(container, prefix, entries)
                    cache.put(path, children)
                    updateLayer(layerIndex) {
                        it.copy(items = sortArchive(children), isEmpty = children.isEmpty(), error = null, isLoading = false)
                    }
                },
                onFailure = { e ->
                    val isEncrypted = e.message?.contains("passphrase", ignoreCase = true) == true ||
                            e.message?.contains("password", ignoreCase = true) == true ||
                            e.message?.contains("decrypt", ignoreCase = true) == true ||
                            e.message?.contains("crypt", ignoreCase = true) == true

                    if (isEncrypted) {
                        _uiState.update {
                            it.copy(
                                passwordPromptContainer = container,
                                passwordError = if (savedPassword != null) "Incorrect password" else null
                            )
                        }
                        updateLayer(layerIndex) {
                            it.copy(isLoading = false)
                        }
                    } else {
                        updateLayer(layerIndex) {
                            it.copy(error = e.message ?: "Cannot open archive", isLoading = false)
                        }
                    }
                },
            )
        }
    }

    private fun sortArchive(items: List<FolderItem>): List<FolderItem> = sortFilesByType(
        items,
        _uiState.value.sortType,
        isDirectory = { it.isDirectory },
        name = { it.name },
        dateModified = { it.dateModified },
        size = { it.fileSize },
        descending = _uiState.value.sortDirection == SortDirection.DESCENDING,
    )

    /**
     * Immediate children of [prefix] within [entries]. Directory levels are
     * synthesized from entry path segments (archives may omit explicit dir
     * entries). Files become [FolderItem]s whose path is an [ArchiveUri] the
     * player can open directly.
     */
    private fun archiveChildren(
        container: String,
        prefix: String,
        entries: List<ArchiveEntry>,
    ): List<FolderItem> {
        val dirNames = LinkedHashSet<String>()
        val files = mutableListOf<FolderItem>()
        for (entry in entries) {
            val fullName = entry.name.trimEnd('/')
            if (fullName.isEmpty() || !fullName.startsWith(prefix)) continue
            val rest = fullName.substring(prefix.length)
            if (rest.isEmpty()) continue
            val slash = rest.indexOf('/')
            if (slash >= 0) {
                dirNames.add(rest.substring(0, slash))
            } else if (entry.isDirectory) {
                dirNames.add(rest)
            } else {
                val pwd = archivePasswords[container]
                files.add(
                    FolderItem(
                        id = ArchiveUri.build(container, entry.name, pwd).hashCode().toLong(),
                        name = rest,
                        path = ArchiveUri.build(container, entry.name, pwd),
                        isDirectory = false,
                        fileSize = entry.size,
                    ),
                )
            }
        }
        val dirs = dirNames.map { dir ->
            val childPrefix = "$prefix$dir/"
            FolderItem(
                id = ArchiveBrowsePath.build(container, childPrefix).hashCode().toLong(),
                name = dir,
                path = ArchiveBrowsePath.build(container, childPrefix),
                isDirectory = true,
            )
        }
        return dirs + files
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
            layer.copy(items = sortFilesByType(
                layer.items,
                state.sortType,
                isDirectory = { it.isDirectory },
                name = { it.name },
                dateModified = { it.dateModified },
                size = { it.fileSize },
                descending = state.sortDirection == SortDirection.DESCENDING,
            ))
        }
        _uiState.update { it.copy(layers = sortedLayers) }
    }

    private val _scrollStates = mutableMapOf<String, SavedScrollPosition>()

    private fun scrollKey(path: String, orientation: Int) = "$path#$orientation"

    private fun otherOrientation(orientation: Int): Int =
        if (orientation == Configuration.ORIENTATION_LANDSCAPE)
            Configuration.ORIENTATION_PORTRAIT
        else Configuration.ORIENTATION_LANDSCAPE

    fun getScrollState(path: String, orientation: Int): SavedScrollPosition {
        _scrollStates[scrollKey(path, orientation)]?.let { return it }
        // No save for this orientation yet. Convert from the other orientation by
        // reusing the same first-visible item + offset (row heights are
        // orientation-independent in this single-column list), so the visual
        // position stays consistent across rotation instead of jumping to the top.
        // isAtEnd is orientation-independent (list length doesn't change on rotation).
        _scrollStates[scrollKey(path, otherOrientation(orientation))]?.let {
            return SavedScrollPosition(it.index, 0, orientation, it.isAtEnd)
        }
        return SavedScrollPosition(0, 0, orientation)
    }

    fun saveScrollState(path: String, index: Int, offset: Int, orientation: Int, isAtEnd: Boolean = false) {
        _scrollStates[scrollKey(path, orientation)] = SavedScrollPosition(index, offset, orientation, isAtEnd)
    }
}

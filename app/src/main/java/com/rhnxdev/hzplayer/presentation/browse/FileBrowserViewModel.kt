package com.rhnxdev.hzplayer.presentation.browse

import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

/**
 * Simple in-memory LRU cache for directory listings.
 * Cleared automatically when the ViewModel is destroyed.
 * Each entry stores a list snapshot keyed by directory path.
 */
private class DirectoryLruCache(private val maxSize: Int = 50) {
    private val map = object : LinkedHashMap<String, List<FolderItem>>(maxSize, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, List<FolderItem>>): Boolean =
            size > maxSize
    }

    @Synchronized fun get(key: String): List<FolderItem>? = map[key]
    @Synchronized fun put(key: String, items: List<FolderItem>) { map[key] = items }
    @Synchronized fun remove(key: String) { map.remove(key) }
    @Synchronized fun clear() { map.clear() }
}

@HiltViewModel
class FileBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    @ApplicationContext private val context: android.content.Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val navigationStack = mutableListOf<String>()
    private val cache = DirectoryLruCache()

    /** Reusable search state holder — exposes [searchQuery] and [isSearchActive] flows. */
    val search = SearchDelegate()

    init {
        loadRoots()
    }

    /** Force-refresh the current directory (pull-to-refresh). */
    fun onRefresh() {
        val mode = _uiState.value.mode
        when (mode) {
            FileBrowserMode.ROOTS -> {
                cache.clear()
                loadRoots()
            }
            FileBrowserMode.BROWSING -> {
                val path = _uiState.value.currentPath
                if (path.isNotEmpty()) {
                    cache.remove(path)
                    browseDirectory(path)
                }
            }
        }
    }

    private fun loadRoots() {
        viewModelScope.launch {
            search.clear()
            _uiState.update { it.copy(isLoading = true, mode = FileBrowserMode.ROOTS) }

            val roots = withContext(Dispatchers.IO) {
                val list = mutableListOf<FolderItem>()

                val internalStorage = Environment.getExternalStorageDirectory()
                if (internalStorage.exists()) {
                    list.add(
                        FolderItem(
                            id = 0,
                            name = "Internal Storage",
                            path = internalStorage.absolutePath,
                            isDirectory = true,
                            freeSpace = internalStorage.freeSpace,
                            totalSpace = internalStorage.totalSpace,
                            childCount = internalStorage.listFiles()?.size ?: 0,
                        ),
                    )
                }

                val externalDirs = context.getExternalFilesDirs(null)
                val seen = mutableSetOf<String>()
                externalDirs.forEachIndexed { index, dir ->
                    if (dir != null) {
                        val basePath = dir.absolutePath
                            .substringBefore("/Android")
                        if (basePath !in seen) {
                            seen.add(basePath)
                            if (basePath != internalStorage.absolutePath) {
                                val file = File(basePath)
                                if (file.exists() && file.isDirectory) {
                                    val label = when {
                                        index == 0 -> "External Storage"
                                        file.totalSpace > 1_000_000_000L -> "SD Card"
                                        else -> "Storage ${index + 1}"
                                    }
                                    list.add(
                                        FolderItem(
                                            id = (100 + index).toLong(),
                                            name = label,
                                            path = file.absolutePath,
                                            isDirectory = true,
                                            freeSpace = file.freeSpace,
                                            totalSpace = file.totalSpace,
                                            childCount = file.listFiles()?.size ?: 0,
                                        ),
                                    )
                                }
                            }
                        }
                    }
                }

                list
            }

            _uiState.update {
                it.copy(roots = roots, isEmpty = roots.isEmpty(), isLoading = false)
            }
        }
    }

    fun onStorageRootClicked(root: FolderItem) {
        navigationStack.clear()
        navigationStack.add("/")
        browseDirectory(root.path)
    }

    fun onFolderClicked(item: FolderItem) {
        if (item.isDirectory) {
            navigationStack.add(_uiState.value.currentPath)
            browseDirectory(item.path)
        }
    }

    fun onBreadcrumbClicked(path: String) {
        val idx = navigationStack.indexOf(path)
        if (idx >= 0) {
            val toRemove = navigationStack.size - idx - 1
            repeat(toRemove) { navigationStack.removeLastOrNull() }
        } else {
            navigationStack.clear()
            navigationStack.add("/")
        }
        browseDirectory(path)
    }

    fun onNavigateUp(): Boolean {
        return if (navigationStack.isNotEmpty()) {
            val previous = navigationStack.removeLast()
            if (previous == "/") { loadRoots(); true }
            else { browseDirectory(previous); true }
        } else {
            loadRoots()
            true
        }
    }

    fun onBackToRoots() {
        navigationStack.clear()
        loadRoots()
    }

    fun onSearchToggle() = search.toggle()
    fun onSearchQueryChanged(query: String) = search.queryChanged(query)
    fun onClearSearch() = search.clear()

    fun onRetry() {
        if (_uiState.value.mode == FileBrowserMode.ROOTS) loadRoots()
        else browseDirectory(_uiState.value.currentPath)
    }

    private fun browseDirectory(path: String) {
        viewModelScope.launch {
            search.clear()
            _uiState.update {
                it.copy(
                    currentPath = path,
                    breadcrumbs = buildBreadcrumbs(path),
                    mode = FileBrowserMode.BROWSING,
                    error = null,
                    isLoading = true,
                )
            }

            // Check in-memory cache first
            val cached = cache.get(path)
            if (cached != null) {
                _uiState.update {
                    it.copy(items = cached, isEmpty = cached.isEmpty(), error = null, isLoading = false)
                }
                return@launch
            }

            try {
                fileRepository.listDirectory(path).collect { items ->
                    cache.put(path, items)
                    _uiState.update {
                        it.copy(
                            items = items,
                            isEmpty = items.isEmpty(),
                            error = null,
                            isLoading = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Failed", isLoading = false)
                }
            }
        }
    }

    private fun buildBreadcrumbs(path: String): List<Breadcrumb> {
        val parts = path.trimStart('/').split("/")
        val crumbs = mutableListOf(Breadcrumb("Device", "/"))
        var accumulated = ""
        for (part in parts) {
            accumulated = "$accumulated/$part"
            crumbs.add(Breadcrumb(part, accumulated))
        }
        return crumbs
    }
}

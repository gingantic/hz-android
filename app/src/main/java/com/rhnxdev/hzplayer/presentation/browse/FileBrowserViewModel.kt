package com.rhnxdev.hzplayer.presentation.browse

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.core.util.DirectoryLruCache
import com.rhnxdev.hzplayer.core.util.buildBreadcrumbs
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
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
class FileBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val userPrefs: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(FileBrowserUiState())
    val uiState: StateFlow<FileBrowserUiState> = _uiState.asStateFlow()

    private val cache = DirectoryLruCache<FolderItem>()
    private var showHidden = false

    val search = SearchDelegate()

    init {
        loadRoots()
        viewModelScope.launch {
            userPrefs.showHiddenFiles.collect { hidden ->
                showHidden = hidden
                val layers = _uiState.value.layers
                if (layers.isNotEmpty()) {
                    val path = layers.last().path
                    if (path.isNotEmpty()) {
                        cache.remove(path)
                        // Re-load the topmost layer with new hidden setting
                        loadDirectory(path, layers.size - 1)
                    }
                }
            }
        }
    }

    fun onRefresh() {
        val state = _uiState.value
        when {
            state.mode == FileBrowserMode.ROOTS -> {
                cache.clear()
                loadRoots()
            }
            state.layers.isNotEmpty() -> {
                val path = state.layers.last().path
                if (path.isNotEmpty()) {
                    cache.remove(path)
                    loadDirectory(path, state.layers.lastIndex)
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
                .catch { /* fallback empty */ }
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

    fun onSearchToggle() = search.toggle()
    fun onSearchQueryChanged(query: String) = search.queryChanged(query)
    fun onClearSearch() = search.clear()

    fun onRetry() {
        val state = _uiState.value
        if (state.mode == FileBrowserMode.ROOTS) loadRoots()
        else if (state.layers.isNotEmpty()) {
            val idx = state.layers.lastIndex
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

    private fun loadDirectory(path: String, layerIndex: Int) {
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
                    cache.put(path, items)
                    updateLayer(layerIndex) {
                        it.copy(items = items, isEmpty = items.isEmpty(), error = null, isLoading = false)
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
}

package com.rhnxdev.hzplayer.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.core.util.DirectoryLruCache
import com.rhnxdev.hzplayer.core.util.ServerDiscoverer
import com.rhnxdev.hzplayer.core.util.buildRemoteBreadcrumbs
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.repository.NetworkRepository
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val remoteBrowseRepository: RemoteBrowseRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val serverDiscoverer: ServerDiscoverer,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState.asStateFlow()

    private val dirCache = DirectoryLruCache<RemoteFileItem>()

    private val sortKey = "network_browser"

    val search = SearchDelegate()

    init {
        android.util.Log.d("NetworkViewModel", "NetworkViewModel initialized")
        observeServers(); observeHistory(); observeDiscovery()
        viewModelScope.launch {
            val savedSort = networkRepository.getSortType(sortKey).first()
            _uiState.update { it.copy(sortType = savedSort) }
        }
        viewModelScope.launch {
            userPreferencesRepository.getViewMode("network_home").collect { mode ->
                _uiState.update { it.copy(isHomeListView = mode == ViewMode.LIST) }
            }
        }
    }

    private fun observeServers() {
        viewModelScope.launch {
            networkRepository.getSavedServers().collect { servers ->
                _uiState.update { it.copy(savedServers = servers) }
            }
        }
    }

    private fun observeHistory() {
        viewModelScope.launch {
            networkRepository.getStreamHistory().collect { history ->
                _uiState.update { it.copy(streamHistory = history) }
            }
        }
    }

    private fun observeDiscovery() {
        viewModelScope.launch {
            serverDiscoverer.discoveredServers.collect { servers ->
                _uiState.update { it.copy(discoveredServers = servers) }
            }
        }
        viewModelScope.launch {
            serverDiscoverer.isOnCompatibleNetwork.collect { compat ->
                _uiState.update { it.copy(isOnCompatibleNetwork = compat) }
            }
        }
        viewModelScope.launch {
            serverDiscoverer.isScanning.collect { scanning ->
                _uiState.update { it.copy(isDiscovering = scanning) }
            }
        }
    }

    // ── Discovery ──────────────────────────────────────────────────────

    fun onScanNetwork() {
        android.util.Log.d("NetworkViewModel", "onScanNetwork: triggering startScan()")
        serverDiscoverer.startScan()
    }

    fun onStopScan() {
        serverDiscoverer.stopScan()
    }

    fun onDiscoveredServerTapped(server: ServerConfig) {
        if (server.username.isBlank()) {
            _uiState.update {
                it.copy(discoveredServerCredential = ServerCredentialRequest(
                    server = server,
                    onProvided = { user, pass, save ->
                        val withCreds = server.copy(username = user, password = pass)
                        if (save) {
                            viewModelScope.launch {
                                networkRepository.saveServer(withCreds)
                                serverDiscoverer.dismissDiscoveredServer(server.host)
                                _uiState.update { s -> s.copy(discoveredServerCredential = null) }
                            }
                        } else {
                            _uiState.update { it.copy(discoveredServerCredential = null) }
                            onBrowseServer(withCreds)
                        }
                    },
                ))
            }
        } else {
            onBrowseServer(server)
        }
    }

    fun onSaveDiscoveredServer(server: ServerConfig) {
        viewModelScope.launch {
            networkRepository.saveServer(server)
            serverDiscoverer.dismissDiscoveredServer(server.host)
        }
    }

    fun onDismissDiscoveredServer(server: ServerConfig) {
        serverDiscoverer.dismissDiscoveredServer(server.host)
    }

    fun onDismissCredentialDialog() {
        _uiState.update { it.copy(discoveredServerCredential = null) }
    }

    fun onStreamUrlChanged(url: String) {
        _uiState.update { it.copy(streamUrl = url, streamUrlError = null) }
    }

    fun onPlayStream(): String? {
        val url = _uiState.value.streamUrl.trim()
        if (!isValidStreamUrl(url)) {
            _uiState.update { it.copy(streamUrlError = "Enter a valid URL (http, https, rtsp)") }
            return null
        }
        viewModelScope.launch {
            networkRepository.addStreamToHistory(url, url.substringAfterLast("/").ifEmpty { url })
        }
        return url
    }

    fun onPlayHistoryItem(item: StreamHistoryItem): String {
        viewModelScope.launch { networkRepository.addStreamToHistory(item.url, item.title) }
        return item.url
    }

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://") ||
            lower.startsWith("rtsp://") || lower.startsWith("rtp://") ||
            lower.startsWith("mms://") || lower.startsWith("mmsh://")
    }

    fun onAddServerClicked() { _uiState.update { it.copy(showServerDialog = true, editingServer = null) } }
    fun onEditServer(server: ServerConfig) { _uiState.update { it.copy(showServerDialog = true, editingServer = server) } }
    fun onDismissServerDialog() { _uiState.update { it.copy(showServerDialog = false, editingServer = null) } }

    fun onSaveServer(server: ServerConfig) {
        viewModelScope.launch {
            if (server.id == 0L) networkRepository.saveServer(server)
            else networkRepository.updateServer(server)
            _uiState.update { it.copy(showServerDialog = false, editingServer = null) }
        }
    }

    fun onDeleteServer(id: Long) { viewModelScope.launch { networkRepository.deleteServer(id) } }

    fun onBrowseServer(server: ServerConfig) {
        dirCache.clear()
        _uiState.update {
            it.copy(
                mode = NetworkScreenMode.SERVER_BROWSE,
                browsingServer = server,
                remoteLayers = emptyList(),
            )
        }
        pushRemoteLayer(server, server.basePath)
    }

    fun onRemoteFolderClicked(item: RemoteFileItem) {
        if (item.isDirectory) {
            val server = _uiState.value.browsingServer ?: return
            pushRemoteLayer(server, item.path)
        }
    }

    fun onRemoteBreadcrumbClicked(path: String) {
        val layers = _uiState.value.remoteLayers
        val idx = layers.indexOfFirst { it.path == path }
        if (idx >= 0) {
            _uiState.update { it.copy(remoteLayers = layers.take(idx + 1)) }
        } else {
            val server = _uiState.value.browsingServer ?: return
            _uiState.update { it.copy(remoteLayers = emptyList()) }
            pushRemoteLayer(server, path)
        }
    }

    fun onRemoteNavigateUp(): Boolean {
        val layers = _uiState.value.remoteLayers
        return if (layers.size > 1) {
            _uiState.update { it.copy(remoteLayers = layers.dropLast(1)) }
            true
        } else {
            onBackToHome(); true
        }
    }

    fun onBackToHome() {
        dirCache.clear()
        _uiState.update {
            it.copy(mode = NetworkScreenMode.HOME, browsingServer = null,
                remoteLayers = emptyList())
        }
    }

    fun buildPlaybackUri(remotePath: String): String? {
        val server = _uiState.value.browsingServer ?: return null
        return remoteBrowseRepository.buildPlaybackUri(server, remotePath)
    }

    fun onRetryBrowse() { onRefreshBrowse() }

    fun onSortChanged(sort: SortType) {
        _uiState.update { it.copy(sortType = sort) }
        viewModelScope.launch {
            networkRepository.setSortType(sortKey, sort)
        }
        reapplyRemoteSort()
    }

    fun onRefreshBrowse() {
        val server = _uiState.value.browsingServer ?: return
        val layers = _uiState.value.remoteLayers
        if (layers.isNotEmpty()) {
            val idx = layers.lastIndex
            val path = layers[idx].path
            dirCache.remove(path)
            loadRemoteDirectory(server, path, idx)
        }
    }

    private fun pushRemoteLayer(server: ServerConfig, path: String) {
        val layer = RemoteDirectoryLayer(
            path = path,
            breadcrumbs = buildRemoteBreadcrumbs(server.name, path),
            isLoading = true,
        )
        _uiState.update {
            it.copy(
                mode = NetworkScreenMode.SERVER_BROWSE,
                remoteLayers = it.remoteLayers + layer,
            )
        }
        loadRemoteDirectory(server, path, _uiState.value.remoteLayers.lastIndex)
    }

    private fun loadRemoteDirectory(server: ServerConfig, path: String, layerIndex: Int) {
        viewModelScope.launch {
            updateRemoteLayer(layerIndex) { it.copy(isLoading = true, error = null) }

            val cached = dirCache.get(path)
            if (cached != null) {
                val sorted = sortRemoteItems(cached, _uiState.value.sortType)
                updateRemoteLayer(layerIndex) {
                    it.copy(items = sorted, isLoading = false)
                }
                return@launch
            }

            val result = remoteBrowseRepository.listDirectory(server, path)
            result.fold(
                onSuccess = { items ->
                    dirCache.put(path, items)
                    val sorted = sortRemoteItems(items, _uiState.value.sortType)
                    updateRemoteLayer(layerIndex) {
                        it.copy(items = sorted, isEmpty = items.isEmpty(), isLoading = false)
                    }
                    // Enrich children in background if there are directories
                    if (items.any { it.isDirectory }) {
                        launch {
                            val enriched = remoteBrowseRepository.enrichDirectory(server, items)
                            dirCache.put(path, enriched)
                            updateRemoteLayer(layerIndex) {
                                it.copy(items = enriched)
                            }
                        }
                    }
                },
                onFailure = { e ->
                    updateRemoteLayer(layerIndex) {
                        it.copy(error = e.message ?: "Connection failed", isLoading = false)
                    }
                },
            )
        }
    }

    private fun updateRemoteLayer(index: Int, transform: (RemoteDirectoryLayer) -> RemoteDirectoryLayer) {
        _uiState.update { state ->
            val layers = state.remoteLayers.toMutableList()
            if (index in layers.indices) {
                layers[index] = transform(layers[index])
            }
            state.copy(remoteLayers = layers)
        }
    }

    private fun reapplyRemoteSort() {
        val state = _uiState.value
        val sortedLayers = state.remoteLayers.map { layer ->
            layer.copy(items = sortRemoteItems(layer.items, state.sortType))
        }
        _uiState.update { it.copy(remoteLayers = sortedLayers) }
    }

    private fun sortRemoteItems(items: List<RemoteFileItem>, sort: SortType): List<RemoteFileItem> {
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

    fun onToggleMediaMode() {
        _uiState.update { it.copy(isMediaMode = !it.isMediaMode) }
    }

    fun onToggleHomeView() {
        val new = !_uiState.value.isHomeListView
        _uiState.update { it.copy(isHomeListView = new) }
        viewModelScope.launch {
            userPreferencesRepository.setViewMode("network_home", if (new) ViewMode.LIST else ViewMode.GRID)
        }
    }

    fun onSearchToggle() = search.toggle()
    fun onSearchQueryChanged(query: String) = search.queryChanged(query)
    fun onClearSearch() = search.clear()

    fun onToggleFavorite(id: Long) { viewModelScope.launch { networkRepository.toggleFavorite(id) } }
    fun onDeleteHistoryItem(id: Long) { viewModelScope.launch { networkRepository.deleteHistoryItem(id) } }
    fun onClearHistory() { viewModelScope.launch { networkRepository.clearHistory() } }

    override fun onCleared() {
        super.onCleared()
        serverDiscoverer.cleanup()
    }
}

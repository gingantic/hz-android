package com.rhnxdev.hzplayer.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.core.util.DirectoryLruCache
import com.rhnxdev.hzplayer.core.util.ServerDiscoverer
import com.rhnxdev.hzplayer.core.util.buildRemoteBreadcrumbs
import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isVideoContentType
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.core.util.probeContentType
import com.rhnxdev.hzplayer.domain.model.RemoteAuthException
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
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import javax.inject.Inject

/** Result of resolving a pasted stream URL: video/audio decision + detected MIME type. */
data class StreamResolution(val isVideo: Boolean, val mimeType: String?)

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

    /** Serializes per-folder child-count updates so concurrent refreshes don't clobber each other. */
    private val countMutex = Mutex()

    private val sortKey = "network_browser"

    val search = SearchDelegate()

    init {
        if (com.rhnxdev.hzplayer.BuildConfig.DEBUG) {
            android.util.Log.d("NetworkViewModel", "NetworkViewModel initialized")
        }
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
            promptCredentials(server)
        } else {
            onBrowseServer(server)
        }
    }

    /**
     * Show the credential dialog for [server]. Used both on first tap of a server with no
     * saved credentials and to re-prompt after a rejected login ([error] set). Applying
     * credentials re-browses with them; ticking "save" persists to the saved-server list.
     */
    private fun promptCredentials(server: ServerConfig, error: String? = null) {
        _uiState.update {
            it.copy(discoveredServerCredential = ServerCredentialRequest(
                server = server,
                error = error,
                onProvided = { user, pass, save ->
                    val withCreds = server.copy(username = user, password = pass)
                    _uiState.update { s -> s.copy(discoveredServerCredential = null) }
                    if (save) {
                        viewModelScope.launch {
                            if (withCreds.id == 0L) networkRepository.saveServer(withCreds)
                            else networkRepository.updateServer(withCreds)
                            serverDiscoverer.dismissDiscoveredServer(server.host)
                        }
                    }
                    onBrowseServer(withCreds)
                },
            ))
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
        _uiState.update { it.copy(streamUrl = url, isStreamUrlError = false) }
    }

    fun onPlayStream(): String? {
        val url = _uiState.value.streamUrl.trim()
        if (!isValidStreamUrl(url)) {
            _uiState.update { it.copy(isStreamUrlError = true) }
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

    /**
     * Resolve whether a pasted stream URL is video and its MIME type, probing the
     * server `Content-Type` for extensionless URLs (bucket URLs). Falls back to
     * video for unknown types (buckets are usually video); ExoPlayer sniffs the
     * container regardless, so playback still works.
     */
    suspend fun resolveStreamMedia(url: String): StreamResolution {
        if (isVideoExtension(url)) return StreamResolution(true, guessMimeType(url) ?: "video/mp4")
        if (isAudioExtension(url)) return StreamResolution(false, guessMimeType(url) ?: "audio/mpeg")
        if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) {
            return StreamResolution(true, guessMimeType(url))
        }
        val ct = probeContentType(url)
        return StreamResolution(isVideoContentType(ct), ct ?: guessMimeType(url))
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
            if (layers[idx].isLoading) return
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
            val minDelayJob = launch { kotlinx.coroutines.delay(300) }
            try {
                updateRemoteLayer(layerIndex) { it.copy(isLoading = true, error = null) }

                val cached = dirCache.get(path)
                if (cached != null) {
                    val sorted = sortRemoteItems(cached, _uiState.value.sortType)
                    minDelayJob.join()
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
                        minDelayJob.join()
                        updateRemoteLayer(layerIndex) {
                            it.copy(items = sorted, isEmpty = items.isEmpty(), isLoading = false)
                        }
                        // Count children per folder on one shared connection. Each folder's
                        // badge updates as soon as its count is ready — list already shown,
                        // no waiting on siblings. Badge hidden (childCount = -1) until set.
                        if (items.any { it.isDirectory }) {
                            launch {
                                remoteBrowseRepository.enrichDirectory(server, items) { folderPath, counts ->
                                    countMutex.withLock {
                                        dirCache.get(path)?.let { cached ->
                                            dirCache.put(path, cached.map {
                                                if (it.path == folderPath) it.copy(
                                                    subfolderCount = counts.folders,
                                                    fileCount = counts.files,
                                                    mediaCount = counts.media,
                                                ) else it
                                            })
                                        }
                                        updateRemoteLayer(layerIndex) { layer ->
                                            layer.copy(items = layer.items.map {
                                                if (it.path == folderPath) it.copy(
                                                    subfolderCount = counts.folders,
                                                    fileCount = counts.files,
                                                    mediaCount = counts.media,
                                                ) else it
                                            })
                                        }
                                    }
                                }
                            }
                        }
                    },
                    onFailure = { e ->
                        minDelayJob.join()
                        if (e is RemoteAuthException) {
                            // Wrong credentials — re-prompt instead of showing a dead-end error.
                            promptCredentials(server, "Wrong username or password. Try again.")
                            updateRemoteLayer(layerIndex) { it.copy(isLoading = false) }
                        } else {
                            updateRemoteLayer(layerIndex) {
                                it.copy(error = friendlyError(e), isLoading = false)
                            }
                        }
                    },
                )
            } finally {
                // Guarantee the refresh indicator can never get stuck, even if the
                // coroutine is cancelled mid-flight.
                minDelayJob.join()
                updateRemoteLayer(layerIndex) { it.copy(isLoading = false) }
            }
        }
    }

    /**
     * Map a raw browse failure to a short, user-readable message. Raw exception text
     * (e.g. "PROPFIND /x returned 404", stack traces) is unhelpful and leaks internals.
     */
    private fun friendlyError(e: Throwable): String = when {
        e is kotlinx.coroutines.TimeoutCancellationException ->
            "Server took too long to respond. Check it's online and try again."
        e is java.net.UnknownHostException ->
            "Can't reach the server. Check the address and your connection."
        e is java.net.ConnectException || e is java.net.SocketTimeoutException ->
            "Couldn't connect to the server. Check it's online and try again."
        e.message?.contains("404") == true -> "Folder not found on the server."
        e.message?.contains("403") == true -> "Access denied for this folder."
        else -> "Couldn't load files. Check the server and try again."
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

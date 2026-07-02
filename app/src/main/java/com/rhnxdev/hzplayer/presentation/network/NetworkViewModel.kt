package com.rhnxdev.hzplayer.presentation.network

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.repository.NetworkRepository
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class NetworkViewModel @Inject constructor(
    private val networkRepository: NetworkRepository,
    private val remoteBrowseRepository: RemoteBrowseRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(NetworkUiState())
    val uiState: StateFlow<NetworkUiState> = _uiState.asStateFlow()

    private val navigationStack = mutableListOf<String>()

    init {
        observeServers()
        observeHistory()
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

    // ── Stream URL ────────────────────────────────────────────

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
            val title = url.substringAfterLast("/").ifEmpty { url }
            networkRepository.addStreamToHistory(url, title)
        }
        return url
    }

    fun onPlayHistoryItem(item: StreamHistoryItem): String {
        viewModelScope.launch {
            networkRepository.addStreamToHistory(item.url, item.title)
        }
        return item.url
    }

    private fun isValidStreamUrl(url: String): Boolean {
        val lower = url.lowercase()
        return lower.startsWith("http://") ||
            lower.startsWith("https://") ||
            lower.startsWith("rtsp://") ||
            lower.startsWith("rtp://") ||
            lower.startsWith("mms://") ||
            lower.startsWith("mmsh://")
    }

    // ── Server management ─────────────────────────────────────

    fun onAddServerClicked() {
        _uiState.update { it.copy(showServerDialog = true, editingServer = null) }
    }

    fun onEditServer(server: ServerConfig) {
        _uiState.update { it.copy(showServerDialog = true, editingServer = server) }
    }

    fun onDismissServerDialog() {
        _uiState.update { it.copy(showServerDialog = false, editingServer = null) }
    }

    fun onSaveServer(server: ServerConfig) {
        viewModelScope.launch {
            if (server.id == 0L) {
                networkRepository.saveServer(server)
            } else {
                networkRepository.updateServer(server)
            }
            _uiState.update { it.copy(showServerDialog = false, editingServer = null) }
        }
    }

    fun onDeleteServer(id: Long) {
        viewModelScope.launch {
            networkRepository.deleteServer(id)
        }
    }

    // ── Remote browsing ───────────────────────────────────────

    fun onBrowseServer(server: ServerConfig) {
        navigationStack.clear()
        _uiState.update {
            it.copy(
                mode = NetworkScreenMode.SERVER_BROWSE,
                browsingServer = server,
                currentRemotePath = server.basePath,
                remoteItems = emptyList(),
                remoteBrowseError = null,
            )
        }
        browseRemoteDirectory(server, server.basePath)
    }

    fun onRemoteFolderClicked(item: RemoteFileItem) {
        if (item.isDirectory) {
            val server = _uiState.value.browsingServer ?: return
            navigationStack.add(_uiState.value.currentRemotePath)
            browseRemoteDirectory(server, item.path)
        }
    }

    fun onRemoteBreadcrumbClicked(path: String) {
        val server = _uiState.value.browsingServer ?: return
        val idx = navigationStack.indexOf(path)
        if (idx >= 0) {
            val toRemove = navigationStack.size - idx - 1
            repeat(toRemove) { navigationStack.removeLastOrNull() }
        } else {
            navigationStack.clear()
        }
        browseRemoteDirectory(server, path)
    }

    fun onRemoteNavigateUp(): Boolean {
        if (navigationStack.isNotEmpty()) {
            val server = _uiState.value.browsingServer ?: return false
            val previous = navigationStack.removeLast()
            browseRemoteDirectory(server, previous)
            return true
        }
        onBackToHome()
        return true
    }

    fun onBackToHome() {
        navigationStack.clear()
        _uiState.update {
            it.copy(
                mode = NetworkScreenMode.HOME,
                browsingServer = null,
                remoteItems = emptyList(),
                remoteBreadcrumbs = emptyList(),
                remoteBrowseError = null,
            )
        }
    }

    fun buildPlaybackUri(remotePath: String): String? {
        val server = _uiState.value.browsingServer ?: return null
        return remoteBrowseRepository.buildPlaybackUri(server, remotePath)
    }

    fun onRetryBrowse() {
        val server = _uiState.value.browsingServer ?: return
        browseRemoteDirectory(server, _uiState.value.currentRemotePath)
    }

    fun onRefreshBrowse() {
        val server = _uiState.value.browsingServer ?: return
        browseRemoteDirectory(server, _uiState.value.currentRemotePath)
    }

    private fun browseRemoteDirectory(server: ServerConfig, path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    remoteBrowseLoading = true,
                    remoteBrowseError = null,
                    currentRemotePath = path,
                    remoteBreadcrumbs = buildRemoteBreadcrumbs(server.name, path),
                )
            }
            val result = remoteBrowseRepository.listDirectory(server, path)
            result.fold(
                onSuccess = { items ->
                    _uiState.update {
                        it.copy(
                            remoteItems = items,
                            remoteBrowseLoading = false,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(
                            remoteBrowseError = e.message ?: "Connection failed",
                            remoteBrowseLoading = false,
                        )
                    }
                },
            )
        }
    }

    private fun buildRemoteBreadcrumbs(serverName: String, path: String): List<RemoteBreadcrumb> {
        val crumbs = mutableListOf(RemoteBreadcrumb(serverName, "/"))
        if (path == "/" || path.isEmpty()) return crumbs
        val parts = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        var accumulated = ""
        for (part in parts) {
            accumulated = "$accumulated/$part"
            crumbs.add(RemoteBreadcrumb(part, accumulated))
        }
        return crumbs
    }

    // ── History ───────────────────────────────────────────────

    fun onToggleFavorite(id: Long) {
        viewModelScope.launch { networkRepository.toggleFavorite(id) }
    }

    fun onDeleteHistoryItem(id: Long) {
        viewModelScope.launch { networkRepository.deleteHistoryItem(id) }
    }

    fun onClearHistory() {
        viewModelScope.launch { networkRepository.clearHistory() }
    }
}

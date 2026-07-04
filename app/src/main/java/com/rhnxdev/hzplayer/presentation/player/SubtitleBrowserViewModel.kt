package com.rhnxdev.hzplayer.presentation.player

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.util.SUBTITLE_EXTENSIONS
import com.rhnxdev.hzplayer.core.util.buildBreadcrumbs
import com.rhnxdev.hzplayer.core.util.buildRemoteBreadcrumbs
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.repository.FileRepository
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

@HiltViewModel
class SubtitleBrowserViewModel @Inject constructor(
    private val fileRepository: FileRepository,
    private val remoteBrowseRepository: RemoteBrowseRepository,
    @ApplicationContext private val context: Context,
) : ViewModel() {

    private val _uiState = MutableStateFlow(SubtitleBrowserUiState())
    val uiState: StateFlow<SubtitleBrowserUiState> = _uiState.asStateFlow()

    private var initialized = false

    fun initVideoUri(videoUri: String?) {
        if (initialized) return
        initialized = true

        if (videoUri.isNullOrEmpty()) {
            loadLocalRoots()
            return
        }

        val parsed = parseNetworkUri(videoUri)
        if (parsed != null) {
            val (server, parentPath) = parsed
            _uiState.update {
                it.copy(
                    remoteServer = server,
                    remoteParentPath = parentPath,
                )
            }
            browseRemoteDirectory(server, parentPath)
        } else {
            val cleanPath = when {
                videoUri.startsWith("file://") -> Uri.parse(videoUri).path ?: ""
                else -> videoUri
            }
            val videoFile = File(cleanPath)
            val parentPath = videoFile.parent ?: "/"
            val parentFile = File(parentPath)
            if (parentFile.exists() && parentFile.isDirectory) {
                browseLocalDirectory(parentPath)
            } else {
                loadLocalRoots()
            }
        }
    }

    private fun parseNetworkUri(uriString: String): Pair<ServerConfig, String>? {
        val uri = try { Uri.parse(uriString) } catch (e: Exception) { return null }
        val scheme = uri.scheme?.lowercase() ?: return null
        val protocol = when (scheme) {
            "ftp" -> NetworkProtocol.FTP
            "sftp" -> NetworkProtocol.SFTP
            "smb" -> NetworkProtocol.SMB
            else -> return null
        }

        val userInfo = uri.userInfo
        var username = ""
        var password = ""
        if (userInfo != null) {
            val parts = userInfo.split(":", limit = 2)
            username = Uri.decode(parts.getOrElse(0) { "" })
            password = Uri.decode(parts.getOrElse(1) { "" })
        }

        val host = uri.host ?: ""
        val port = if (uri.port != -1) uri.port else {
            when (protocol) {
                NetworkProtocol.FTP -> 21
                NetworkProtocol.SFTP -> 22
                NetworkProtocol.SMB -> 445
            }
        }

        val fullPath = uri.path ?: "/"
        val lastSlash = fullPath.lastIndexOf('/')
        val parentPath = if (lastSlash > 0) fullPath.substring(0, lastSlash) else "/"

        val name = "${protocol.name} Server (${host})"
        val serverConfig = ServerConfig(
            name = name,
            protocol = protocol,
            host = host,
            port = port,
            username = username,
            password = password,
            basePath = "/"
        )
        return Pair(serverConfig, parentPath)
    }

    fun loadLocalRoots() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, mode = SubtitleBrowserMode.ROOTS, error = null) }
            fileRepository.getStorageRoots()
                .catch { /* fallback empty */ }
                .collect { roots ->
                    _uiState.update {
                        it.copy(localRoots = roots, isLoading = false, isEmpty = roots.isEmpty() && it.remoteServer == null)
                    }
                }
        }
    }

    fun onStorageRootClicked(root: FolderItem) {
        browseLocalDirectory(root.path)
    }

    fun onLocalFolderClicked(item: FolderItem) {
        if (item.isDirectory) {
            browseLocalDirectory(item.path)
        }
    }

    fun onLocalBreadcrumbClicked(path: String) {
        if (path == "/") loadLocalRoots()
        else browseLocalDirectory(path)
    }

    private fun browseLocalDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentPath = path,
                    localBreadcrumbs = buildBreadcrumbs(path),
                    mode = SubtitleBrowserMode.BROWSING_LOCAL,
                    error = null,
                    isLoading = true,
                )
            }
            try {
                fileRepository.listDirectory(path).collect { items ->
                    val filtered = items.filter { item ->
                        item.isDirectory || item.name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS
                    }
                    _uiState.update {
                        it.copy(localItems = filtered, isEmpty = filtered.isEmpty(), error = null, isLoading = false)
                    }
                }
            } catch (e: Exception) {
                _uiState.update { it.copy(error = e.message ?: "Failed", isLoading = false) }
            }
        }
    }

    fun onRemoteFolderClicked(item: RemoteFileItem) {
        if (item.isDirectory) {
            val server = _uiState.value.remoteServer ?: return
            browseRemoteDirectory(server, item.path)
        }
    }

    fun onRemoteBreadcrumbClicked(path: String) {
        val server = _uiState.value.remoteServer ?: return
        if (path == "/") loadLocalRoots()
        else browseRemoteDirectory(server, path)
    }

    fun browseRemoteDirectory(server: ServerConfig, path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentPath = path,
                    remoteBreadcrumbs = buildRemoteBreadcrumbs(server.name, path),
                    mode = SubtitleBrowserMode.BROWSING_REMOTE,
                    error = null,
                    isLoading = true,
                )
            }
            val result = remoteBrowseRepository.listDirectory(server, path)
            result.fold(
                onSuccess = { items ->
                    val filtered = items.filter { item ->
                        item.isDirectory || item.name.substringAfterLast('.', "").lowercase() in SUBTITLE_EXTENSIONS
                    }
                    _uiState.update {
                        it.copy(remoteItems = filtered, isEmpty = filtered.isEmpty(), error = null, isLoading = false)
                    }
                },
                onFailure = { e ->
                    _uiState.update { it.copy(error = e.message ?: "Connection failed", isLoading = false) }
                }
            )
        }
    }

    fun onNavigateUp(): Boolean {
        val state = _uiState.value
        when (state.mode) {
            SubtitleBrowserMode.ROOTS -> return false
            SubtitleBrowserMode.BROWSING_LOCAL -> {
                val isRootPath = state.localRoots.any { it.path == state.currentPath } || state.currentPath == "/" || state.currentPath.isEmpty()
                if (isRootPath) loadLocalRoots()
                else browseLocalDirectory(File(state.currentPath).parent ?: "/")
                return true
            }
            SubtitleBrowserMode.BROWSING_REMOTE -> {
                if (state.currentPath == "/" || state.currentPath.isEmpty()) loadLocalRoots()
                else {
                    val server = state.remoteServer ?: return false
                    val cleanPath = state.currentPath.trimEnd('/')
                    val lastSlash = cleanPath.lastIndexOf('/')
                    val parent = if (lastSlash >= 0) cleanPath.substring(0, lastSlash).ifEmpty { "/" } else "/"
                    browseRemoteDirectory(server, parent)
                }
                return true
            }
        }
    }

    fun buildRemotePlaybackUri(remotePath: String): Uri? {
        val server = _uiState.value.remoteServer ?: return null
        return Uri.parse(remoteBrowseRepository.buildPlaybackUri(server, remotePath))
    }
}

package com.rhnxdev.hzplayer.presentation.player

import android.content.Context
import android.net.Uri
import android.os.Environment
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import javax.inject.Inject

private val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "lrc")

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
            // Store remote server info and start directly in the video's remote folder.
            // User can navigate back via back button or breadcrumbs to reach storage selection.
            _uiState.update {
                it.copy(
                    remoteServer = server,
                    remoteParentPath = parentPath,
                )
            }
            browseRemoteDirectory(server, parentPath)
        } else {
            // Local file or content URI — navigate into the video's parent folder directly
            val cleanPath = when {
                videoUri.startsWith("file://") -> Uri.parse(videoUri).path ?: ""
                else -> videoUri
            }
            val videoFile = File(cleanPath)
            val parentPath = videoFile.parent ?: Environment.getExternalStorageDirectory().absolutePath
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

    // ── Local Navigation ───────────────────────────────────────

    fun loadLocalRoots() {
        viewModelScope.launch {
            _uiState.update { it.copy(isLoading = true, mode = SubtitleBrowserMode.ROOTS, error = null) }
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
                            childCount = try {
                                internalStorage.listFiles()?.size ?: 0
                            } catch (e: Exception) {
                                0
                            },
                        )
                    )
                }

                val externalDirs = context.getExternalFilesDirs(null)
                val seen = mutableSetOf<String>()
                externalDirs.forEachIndexed { index, dir ->
                    if (dir != null) {
                        val basePath = dir.absolutePath.substringBefore("/Android")
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
                                            childCount = try {
                                                file.listFiles()?.size ?: 0
                                            } catch (e: Exception) {
                                                0
                                            },
                                        )
                                    )
                                }
                            }
                        }
                    }
                }
                list
            }
            _uiState.update {
                it.copy(localRoots = roots, isLoading = false, isEmpty = roots.isEmpty() && it.remoteServer == null)
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
        if (path == "/") {
            loadLocalRoots()
        } else {
            browseLocalDirectory(path)
        }
    }

    private fun browseLocalDirectory(path: String) {
        viewModelScope.launch {
            _uiState.update {
                it.copy(
                    currentPath = path,
                    localBreadcrumbs = buildLocalBreadcrumbs(path),
                    mode = SubtitleBrowserMode.BROWSING_LOCAL,
                    error = null,
                    isLoading = true,
                )
            }

            try {
                fileRepository.listDirectory(path).collect { items ->
                    val filtered = items.filter { item ->
                        item.isDirectory || SUBTITLE_EXTENSIONS.contains(item.name.substringAfterLast('.', "").lowercase())
                    }
                    _uiState.update {
                        it.copy(
                            localItems = filtered,
                            isEmpty = filtered.isEmpty(),
                            error = null,
                            isLoading = false,
                        )
                    }
                }
            } catch (e: Exception) {
                _uiState.update {
                    it.copy(error = e.message ?: "Failed to read directory", isLoading = false)
                }
            }
        }
    }

    private fun buildLocalBreadcrumbs(path: String): List<SubtitleBreadcrumb> {
        val parts = path.trimStart('/').split("/")
        val crumbs = mutableListOf(SubtitleBreadcrumb("Device", "/"))
        var accumulated = ""
        for (part in parts) {
            if (part.isEmpty()) continue
            accumulated = "$accumulated/$part"
            crumbs.add(SubtitleBreadcrumb(part, accumulated))
        }
        return crumbs
    }

    // ── Remote Navigation ──────────────────────────────────────

    fun onRemoteFolderClicked(item: RemoteFileItem) {
        if (item.isDirectory) {
            val server = _uiState.value.remoteServer ?: return
            browseRemoteDirectory(server, item.path)
        }
    }

    fun onRemoteBreadcrumbClicked(path: String) {
        val server = _uiState.value.remoteServer ?: return
        if (path == "/") {
            // First crumb (server name) tapped → go to storage selection
            loadLocalRoots()
        } else {
            browseRemoteDirectory(server, path)
        }
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
                        item.isDirectory || SUBTITLE_EXTENSIONS.contains(item.name.substringAfterLast('.', "").lowercase())
                    }
                    _uiState.update {
                        it.copy(
                            remoteItems = filtered,
                            isEmpty = filtered.isEmpty(),
                            error = null,
                            isLoading = false,
                        )
                    }
                },
                onFailure = { e ->
                    _uiState.update {
                        it.copy(error = e.message ?: "Connection failed", isLoading = false)
                    }
                }
            )
        }
    }

    private fun buildRemoteBreadcrumbs(serverName: String, path: String): List<SubtitleBreadcrumb> {
        val crumbs = mutableListOf(SubtitleBreadcrumb(serverName, "/"))
        if (path == "/" || path.isEmpty()) return crumbs
        val parts = path.trimStart('/').split("/").filter { it.isNotEmpty() }
        var accumulated = ""
        for (part in parts) {
            accumulated = "$accumulated/$part"
            crumbs.add(SubtitleBreadcrumb(part, accumulated))
        }
        return crumbs
    }

    // ── Global Back Navigation ─────────────────────────────────

    fun onNavigateUp(): Boolean {
        val state = _uiState.value
        when (state.mode) {
            SubtitleBrowserMode.ROOTS -> return false
            SubtitleBrowserMode.BROWSING_LOCAL -> {
                val isRootPath = state.localRoots.any { it.path == state.currentPath } || state.currentPath == "/" || state.currentPath.isEmpty()
                if (isRootPath) {
                    loadLocalRoots()
                } else {
                    val parent = File(state.currentPath).parent ?: "/"
                    browseLocalDirectory(parent)
                }
                return true
            }
            SubtitleBrowserMode.BROWSING_REMOTE -> {
                val isRootPath = state.currentPath == "/" || state.currentPath.isEmpty()
                if (isRootPath) {
                    loadLocalRoots()
                } else {
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
        val uriStr = remoteBrowseRepository.buildPlaybackUri(server, remotePath)
        return Uri.parse(uriStr)
    }
}

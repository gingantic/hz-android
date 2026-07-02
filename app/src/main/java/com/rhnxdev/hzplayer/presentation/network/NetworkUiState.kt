package com.rhnxdev.hzplayer.presentation.network

import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem

data class NetworkUiState(
    val mode: NetworkScreenMode = NetworkScreenMode.HOME,
    val streamUrl: String = "",
    val streamUrlError: String? = null,
    val savedServers: List<ServerConfig> = emptyList(),
    val streamHistory: List<StreamHistoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,

    val showServerDialog: Boolean = false,
    val editingServer: ServerConfig? = null,

    val browsingServer: ServerConfig? = null,
    val currentRemotePath: String = "/",
    val remoteBreadcrumbs: List<RemoteBreadcrumb> = emptyList(),
    val remoteItems: List<RemoteFileItem> = emptyList(),
    val remoteBrowseLoading: Boolean = false,
    val remoteBrowseError: String? = null,
)

enum class NetworkScreenMode {
    HOME,
    SERVER_BROWSE,
}

data class RemoteBreadcrumb(
    val name: String,
    val path: String,
)

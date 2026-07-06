package com.rhnxdev.hzplayer.presentation.network

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortType

data class RemoteDirectoryLayer(
    val path: String = "",
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
)

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
    /** Layers in order: index 0 = root, last = topmost visible directory */
    val remoteLayers: List<RemoteDirectoryLayer> = emptyList(),
    val sortType: SortType = SortType.TITLE,
    val isMediaMode: Boolean = false,
)

enum class NetworkScreenMode {
    HOME,
    SERVER_BROWSE,
}

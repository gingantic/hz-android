package com.rhnxdev.hzplayer.presentation.network

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import androidx.compose.runtime.Immutable

@Immutable
data class RemoteDirectoryLayer(
    val path: String = "",
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<RemoteFileItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
)

@Immutable
data class NetworkUiState(
    val mode: NetworkScreenMode = NetworkScreenMode.HOME,
    val streamUrl: String = "",
    val isStreamUrlError: Boolean = false,
    val savedServers: List<ServerConfig> = emptyList(),
    val streamHistory: List<StreamHistoryItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,

    val showServerDialog: Boolean = false,
    val editingServer: ServerConfig? = null,

    // Discovery
    val discoveredServers: List<ServerConfig> = emptyList(),
    val isOnCompatibleNetwork: Boolean = false,
    val isDiscovering: Boolean = false,
    val discoveredServerCredential: ServerCredentialRequest? = null,

    val browsingServer: ServerConfig? = null,
    /** Layers in order: index 0 = root, last = topmost visible directory */
    val remoteLayers: List<RemoteDirectoryLayer> = emptyList(),
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    val isMediaMode: Boolean = false,
    val isHomeListView: Boolean = false,
)

/**
 * Carries the server that needs credentials and a callback to apply them.
 * ponytail: Pair-like holder rather than a sealed class — only one credential flow exists.
 */
data class ServerCredentialRequest(
    val server: ServerConfig,
    val onProvided: (username: String, password: String, saveToSaved: Boolean) -> Unit,
    /** Non-null when re-prompting after a rejected login — shown as an error in the dialog. */
    val error: String? = null,
)

enum class NetworkScreenMode {
    HOME,
    SERVER_BROWSE,
}

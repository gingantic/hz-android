package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig

enum class SubtitleBrowserMode {
    ROOTS,
    BROWSING_LOCAL,
    BROWSING_REMOTE
}

data class SubtitleBrowserUiState(
    val mode: SubtitleBrowserMode = SubtitleBrowserMode.ROOTS,
    val currentPath: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,

    // Local storage state
    val localRoots: List<FolderItem> = emptyList(),
    val localItems: List<FolderItem> = emptyList(),
    val localBreadcrumbs: List<BreadcrumbItem> = emptyList(),

    // Remote storage state
    val remoteServer: ServerConfig? = null,
    val remoteParentPath: String? = null,
    val remoteItems: List<RemoteFileItem> = emptyList(),
    val remoteBreadcrumbs: List<BreadcrumbItem> = emptyList(),
)

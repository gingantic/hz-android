package com.rhnxdev.hzplayer.presentation.browse

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import com.rhnxdev.hzplayer.domain.model.FolderItem

/**
 * One directory level in the browsing stack.
 * Each layer has its own fields so scroll state is naturally preserved —
 * the composable stays in the tree when a new layer is pushed on top.
 */
data class DirectoryLayer(
    val path: String = "",
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
)

data class FileBrowserUiState(
    val mode: FileBrowserMode = FileBrowserMode.ROOTS,
    val roots: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Layers in order: index 0 = first directory, last = topmost visible */
    val layers: List<DirectoryLayer> = emptyList(),
)

enum class FileBrowserMode {
    ROOTS,
    BROWSING,
}

package com.rhnxdev.hzplayer.presentation.browse

import com.rhnxdev.hzplayer.domain.model.FolderItem

data class FileBrowserUiState(
    val mode: FileBrowserMode = FileBrowserMode.ROOTS,
    val roots: List<FolderItem> = emptyList(),
    val currentPath: String = "/",
    val breadcrumbs: List<Breadcrumb> = emptyList(),
    val items: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
)

enum class FileBrowserMode {
    ROOTS,
    BROWSING,
}

data class Breadcrumb(
    val name: String,
    val path: String,
)

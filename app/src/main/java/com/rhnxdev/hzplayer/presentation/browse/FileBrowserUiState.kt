package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.ui.graphics.vector.ImageVector
import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType

import androidx.compose.runtime.Immutable

/**
 * One directory level in the browsing stack.
 * Each layer has its own fields so scroll state is naturally preserved —
 * the composable stays in the tree when a new layer is pushed on top.
 */
@Immutable
data class DirectoryLayer(
    val path: String = "",
    val breadcrumbs: List<BreadcrumbItem> = emptyList(),
    val items: List<FolderItem> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null,
    val isEmpty: Boolean = false,
)

@Immutable
data class FavoriteShortcut(
    val name: String,
    val path: String,
    val icon: ImageVector,
    val itemCount: Int = 0,
)

/**
 * A file or folder staged for a paste operation.
 * [isCut] true = paste performs a move, false = paste performs a copy.
 */
@Immutable
data class FileClipboard(
    val item: FolderItem,
    val isCut: Boolean,
)

@Immutable
data class FileBrowserUiState(
    val mode: FileBrowserMode = FileBrowserMode.ROOTS,
    val roots: List<FolderItem> = emptyList(),
    val favorites: List<FavoriteShortcut> = emptyList(),
    val quickAccessPaths: Set<String> = emptySet(),
    val isLoading: Boolean = false,
    val error: String? = null,
    /** Layers in order: index 0 = first directory, last = topmost visible */
    val layers: List<DirectoryLayer> = emptyList(),
    val sortType: SortType = SortType.TITLE,
    val sortDirection: SortDirection = SortDirection.ASCENDING,
    /** When true, browsing shows only video files (+ folders) as large thumbnails. */
    val isMediaMode: Boolean = false,
    val passwordPromptContainer: String? = null,
    val passwordError: String? = null,
    val solidArchiveWarningContainer: FolderItem? = null,
    /** Pending cut/copy source; a paste bar is shown while non-null. */
    val clipboard: FileClipboard? = null,
    /** True while a paste (copy/move) is running on disk. */
    val isPasting: Boolean = false,
    /** One-shot user feedback for file operations; cleared via onFileOpMessageShown(). */
    val fileOpMessage: String? = null,
    /** True when a paste was blocked because "All files access" is not granted. */
    val showAllFilesAccessPrompt: Boolean = false,
    /** Item awaiting delete confirmation; a dialog is shown while non-null. */
    val deleteConfirmItem: FolderItem? = null,
    /**
     * Item inside its undo grace period: hidden from the list, not yet removed
     * from disk. An undo snackbar is shown while non-null.
     */
    val deleteGraceItem: FolderItem? = null,
    /** True while a delete is running on disk. */
    val isDeleting: Boolean = false,
)

enum class FileBrowserMode {
    ROOTS,
    BROWSING,
}

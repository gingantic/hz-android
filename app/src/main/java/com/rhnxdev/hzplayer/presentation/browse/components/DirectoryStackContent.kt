package com.rhnxdev.hzplayer.presentation.browse.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.BreadcrumbBar
import com.rhnxdev.hzplayer.core.components.DirectoryBrowsePane
import com.rhnxdev.hzplayer.core.components.FileItemData
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.ArchiveBrowsePath
import com.rhnxdev.hzplayer.core.util.isArchiveExtension
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isBinaryExtension
import com.rhnxdev.hzplayer.core.util.isDocumentExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FileMediaTypeFilter
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.presentation.browse.DirectoryLayer
import com.rhnxdev.hzplayer.presentation.browse.SavedScrollPosition
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter

/**
 * Render the active directory layer. Older layers are saved by [FileBrowserViewModel]
 * and restored on demand, avoiding rendering up to 32 offscreen composable layer trees.
 */
@Composable
fun DirectoryStackContent(
    layers: List<DirectoryLayer>,
    searchQuery: String,
    isSearchActive: Boolean,
    mediaMode: Boolean,
    quickAccessPaths: Set<String>,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    getScrollState: (String, Int) -> SavedScrollPosition,
    saveScrollState: (String, Int, Int, Int, Boolean) -> Unit,
    fullScreenOverlay: Boolean = false,
    mediaTypeFilter: FileMediaTypeFilter = FileMediaTypeFilter.ALL,
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAsAudio: (FolderItem) -> Unit = {},
    onPlayAllFolder: ((FolderItem) -> Unit)? = null,
    onCutItem: ((FolderItem) -> Unit)? = null,
    onCopyItem: ((FolderItem) -> Unit)? = null,
    onDeleteItem: ((FolderItem) -> Unit)? = null,
    onToggleFavorite: (FileItemData) -> Unit = {},
    onListAtEndChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val currentLayer = layers.lastOrNull() ?: return@Box
        key(currentLayer.path) {
            DirectoryLayerView(
                layer = currentLayer,
                searchQuery = searchQuery,
                isSearchActive = isSearchActive,
                mediaMode = mediaMode,
                mediaTypeFilter = mediaTypeFilter,
                quickAccessPaths = quickAccessPaths,
                onFolderClicked = onFolderClicked,
                onBreadcrumbClicked = onBreadcrumbClicked,
                onRetry = onRetry,
                onRefresh = onRefresh,
                getScrollState = getScrollState,
                saveScrollState = saveScrollState,
                fullScreenOverlay = fullScreenOverlay,
                isTopLayer = true,
                onFileClicked = onFileClicked,
                onPlayAsAudio = onPlayAsAudio,
                onPlayAllFolder = onPlayAllFolder,
                onCutItem = onCutItem,
                onCopyItem = onCopyItem,
                onDeleteItem = onDeleteItem,
                onToggleFavorite = onToggleFavorite,
                onListAtEndChanged = onListAtEndChanged,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

@Composable
private fun DirectoryLayerView(
    layer: DirectoryLayer,
    searchQuery: String,
    isSearchActive: Boolean,
    mediaMode: Boolean,
    mediaTypeFilter: FileMediaTypeFilter,
    quickAccessPaths: Set<String>,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    getScrollState: (String, Int) -> SavedScrollPosition,
    saveScrollState: (String, Int, Int, Int, Boolean) -> Unit,
    fullScreenOverlay: Boolean = false,
    isTopLayer: Boolean = false,
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAsAudio: (FolderItem) -> Unit = {},
    onPlayAllFolder: ((FolderItem) -> Unit)? = null,
    onCutItem: ((FolderItem) -> Unit)? = null,
    onCopyItem: ((FolderItem) -> Unit)? = null,
    onDeleteItem: ((FolderItem) -> Unit)? = null,
    onToggleFavorite: (FileItemData) -> Unit = {},
    onListAtEndChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentOrientation = LocalConfiguration.current.orientation

    val saved = remember(layer.path, currentOrientation) { getScrollState(layer.path, currentOrientation) }
    val initialIndex = saved.index
    val initialOffset = saved.offset
    val listState = remember<LazyListState>(layer.path) {
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = initialOffset,
        )
    }

    LaunchedEffect(currentOrientation, layer.path) {
        val s = getScrollState(layer.path, currentOrientation)
        val total = listState.layoutInfo.totalItemsCount
        if (s.isAtEnd && total > 0) {
            listState.scrollToItem(total - 1, 0)
        } else {
            listState.scrollToItem(s.index, s.offset)
        }
    }

    LaunchedEffect(listState, layer.path) {
        snapshotFlow { listState.isScrollInProgress }
            .distinctUntilChanged()
            .drop(1)
            .filter { !it }
            .collect {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isAtEnd = info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
                saveScrollState(
                    layer.path,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    currentOrientation,
                    isAtEnd,
                )
            }
    }

    LaunchedEffect(listState, layer.path) {
        snapshotFlow { listState.canScrollBackward && !listState.canScrollForward }
            .collect { onListAtEndChanged(it) }
    }

    LaunchedEffect(fullScreenOverlay) {
        if (!fullScreenOverlay && isTopLayer) {
            val s = getScrollState(layer.path, currentOrientation)
            val total = listState.layoutInfo.totalItemsCount
            if (s.isAtEnd && total > 0) {
                listState.scrollToItem(total - 1, 0)
            } else {
                listState.scrollToItem(s.index, s.offset)
            }
        }
    }

    val visibleItems = remember(layer.items, mediaMode, mediaTypeFilter) {
        layer.items.filter { item ->
            if (item.isDirectory) true
            else {
                when (mediaTypeFilter) {
                    FileMediaTypeFilter.VIDEOS -> item.isVideo()
                    FileMediaTypeFilter.AUDIO -> item.isAudio()
                    FileMediaTypeFilter.ARCHIVES -> isArchiveExtension(item.name)
                    FileMediaTypeFilter.ALL -> {
                        if (mediaMode) item.isVideo()
                        else (!isDocumentExtension(item.name) && !isBinaryExtension(item.name))
                    }
                }
            }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(
            breadcrumbs = layer.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        if (visibleItems.isNotEmpty()) {
            val folders = visibleItems.count { it.isDirectory }
            val others = visibleItems.count { !it.isDirectory }
            Text(
                text = if (mediaMode) {
                    stringResource(R.string.dir_summary_media, folders, others)
                } else {
                    stringResource(R.string.dir_summary, folders, others)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
            )
        }

        DirectoryBrowsePane(
            items = remember(visibleItems) { visibleItems.map { it.toFileItemData() } },
            isLoading = layer.isLoading,
            isEmpty = if (mediaMode || mediaTypeFilter != FileMediaTypeFilter.ALL) visibleItems.isEmpty() else layer.isEmpty,
            error = layer.error,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
            mediaMode = mediaMode,
            onItemClick = { data ->
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isAtEnd = info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
                saveScrollState(
                    layer.path,
                    listState.firstVisibleItemIndex,
                    listState.firstVisibleItemScrollOffset,
                    currentOrientation,
                    isAtEnd,
                )
                val item = layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }
                    ?: FolderItem(
                        id = data.id.toLongOrNull() ?: 0L,
                        name = data.name,
                        path = data.path,
                        isDirectory = data.isDirectory,
                        fileSize = data.fileSize,
                        mimeType = data.mimeType,
                    )
                if (item.isDirectory) {
                    onFolderClicked(item)
                } else {
                    onFileClicked(item)
                }
            },
            onPlayAsAudio = { data ->
                val item = layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }
                if (item != null) onPlayAsAudio(item)
            },
            onPlayAllFolder = { data ->
                val item = layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }
                if (item != null && onPlayAllFolder != null) onPlayAllFolder(item)
            },
            onCutItem = if (onCutItem != null && ArchiveBrowsePath.isRealFilePath(layer.path)) {
                { data ->
                    layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }?.let(onCutItem)
                }
            } else null,
            onCopyItem = if (onCopyItem != null && ArchiveBrowsePath.isRealFilePath(layer.path)) {
                { data ->
                    layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }?.let(onCopyItem)
                }
            } else null,
            onDeleteItem = if (onDeleteItem != null && ArchiveBrowsePath.isRealFilePath(layer.path)) {
                { data ->
                    layer.items.firstOrNull { it.id.toString() == data.id || it.path == data.path }?.let(onDeleteItem)
                }
            } else null,
            quickAccessPaths = quickAccessPaths,
            onToggleFavorite = onToggleFavorite,
            onRefresh = onRefresh,
            onRetry = onRetry,
            listState = listState,
        )
    }
}

private fun FolderItem.toFileItemData(): FileItemData = FileItemData(
    id = id.toString(),
    name = name,
    path = path,
    isDirectory = isDirectory,
    fileSize = fileSize,
    childCount = childCount,
    subfolderCount = subfolderCount,
    fileCount = fileCount,
    mediaCount = mediaCount,
    mimeType = mimeType,
    dateModified = dateModified,
    durationMs = durationMs,
    playbackPositionMs = playbackPositionMs,
    resolution = resolution,
    dateAdded = dateAdded,
)

private fun FolderItem.isVideo(): Boolean =
    mimeType?.startsWith("video") == true || isVideoExtension(name)

private fun FolderItem.isAudio(): Boolean =
    mimeType?.startsWith("audio") == true || isAudioExtension(name)

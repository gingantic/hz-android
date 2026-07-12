package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.BreadcrumbBar
import com.rhnxdev.hzplayer.core.components.DirectoryBrowsePane
import com.rhnxdev.hzplayer.core.components.FileItemData
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.isBinaryExtension
import com.rhnxdev.hzplayer.core.util.isDocumentExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAllVideos: (List<VideoItem>) -> Unit = {},
    fullScreenOverlay: Boolean = false,
    isActive: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

    val onNavigateUp: (() -> Unit)? =
        if (uiState.mode == FileBrowserMode.BROWSING) {
            { viewModel.onNavigateUp() }
        } else null

    HzPlayerSearchableScaffold(
        title = if (uiState.mode == FileBrowserMode.ROOTS) "Browse" else "Files",
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        onSearchToggle = viewModel::onSearchToggle,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::onClearSearch,
        onNavigateUp = onNavigateUp,
        searchPlaceholder = "Search files...",
        fullScreenOverlay = fullScreenOverlay,
        isActive = isActive,
        actions = {
            if (uiState.mode == FileBrowserMode.BROWSING) {
                if (!isSearchActive) {
                    androidx.compose.material3.IconButton(onClick = viewModel::onToggleMediaMode) {
                        androidx.compose.material3.Icon(
                            imageVector = if (uiState.isMediaMode) Icons.AutoMirrored.Filled.ViewList
                            else Icons.Filled.PhotoLibrary,
                            contentDescription = if (uiState.isMediaMode) "List view" else "Media view",
                        )
                    }
                }
                var showSortMenu by remember { mutableStateOf(false) }
                androidx.compose.material3.IconButton(onClick = { showSortMenu = true }) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.browse_sort_cd),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    val sortOptions = listOf(
                        SortType.TITLE to stringResource(R.string.sort_by_name),
                        SortType.DATE_MODIFIED to stringResource(R.string.sort_by_date),
                        SortType.FILE_SIZE to stringResource(R.string.sort_by_size),
                    )
                    sortOptions.forEach { (type, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { androidx.compose.material3.Text(label) },
                            onClick = {
                                viewModel.onSortChanged(type, uiState.sortDirection)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortType == type) {
                                {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                    )
                                }
                            } else null,
                        )
                    }
                    androidx.compose.material3.HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(stringResource(R.string.sort_ascending)) },
                        onClick = {
                            viewModel.onSortChanged(uiState.sortType, SortDirection.ASCENDING)
                            showSortMenu = false
                        },
                        leadingIcon = if (uiState.sortDirection == SortDirection.ASCENDING) {
                            { androidx.compose.material3.Icon(Icons.Filled.Check, null) }
                        } else null,
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(stringResource(R.string.sort_descending)) },
                        onClick = {
                            viewModel.onSortChanged(uiState.sortType, SortDirection.DESCENDING)
                            showSortMenu = false
                        },
                        leadingIcon = if (uiState.sortDirection == SortDirection.DESCENDING) {
                            { androidx.compose.material3.Icon(Icons.Filled.Check, null) }
                        } else null,
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState.mode) {
                FileBrowserMode.ROOTS -> StorageRootsContent(
                    roots = uiState.roots,
                    favorites = uiState.favorites,
                    isLoading = uiState.isLoading,
                    onRootClicked = viewModel::onStorageRootClicked,
                    onFavoriteClicked = viewModel::onFavoriteClicked,
                    onRefresh = viewModel::onRefresh,
                )
                FileBrowserMode.BROWSING -> {
                    DirectoryStackContent(
                        layers = uiState.layers,
                        searchQuery = searchQuery,
                        isSearchActive = isSearchActive,
                        mediaMode = uiState.isMediaMode,
                        onFolderClicked = viewModel::onFolderClicked,
                        onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                        onRetry = viewModel::onRetry,
                        onRefresh = viewModel::onRefresh,
                        onFileClicked = onFileClicked,
                    )

                    // Play All FAB
                    val hasVideos = uiState.layers.lastOrNull()?.items?.any {
                        !it.isDirectory && (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name))
                    } == true
                    if (hasVideos && !isSearchActive) {
                        FloatingActionButton(
                            onClick = {
                                val playlist = viewModel.collectVideoPlaylist()
                                if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                            containerColor = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            elevation = FloatingActionButtonDefaults.elevation(
                                defaultElevation = 6.dp,
                                pressedElevation = 12.dp
                            )
                        ) {
                            Icon(
                                imageVector = Icons.Default.PlayArrow,
                                contentDescription = stringResource(R.string.play_all_cd),
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageRootsContent(
    roots: List<FolderItem>,
    favorites: List<FavoriteShortcut>,
    isLoading: Boolean,
    onRootClicked: (FolderItem) -> Unit,
    onFavoriteClicked: (FavoriteShortcut) -> Unit,
    onRefresh: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            isLoading && roots.isEmpty() -> {
                MediaLoadingState(itemCount = 3, shape = ShimmerShape.STORAGE_ROOT, modifier = Modifier.fillMaxSize())
            }
            roots.isEmpty() -> {
                Box(
                    modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center
                ) {
                        MediaEmptyState(
                            icon = Icons.Filled.Storage,
                            title = stringResource(R.string.storage_empty_title),
                            subtitle = stringResource(R.string.storage_empty_subtitle),
                            modifier = Modifier.fillMaxSize(),
                        )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    // Storage section
                    item {
                        Text(
                            text = stringResource(R.string.select_storage),
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(bottom = Spacing.sm),
                        )
                    }
                    items(roots, key = { it.id }) { root ->
                        val freeStr = com.rhnxdev.hzplayer.core.util.formatFileSize(root.freeSpace)
                        val totalStr = com.rhnxdev.hzplayer.core.util.formatFileSize(root.totalSpace)
                        StorageRootCard(
                            name = root.name,
                            storageInfo = stringResource(R.string.storage_info, freeStr, totalStr),
                            itemCount = root.childCount,
                            onClick = { onRootClicked(root) },
                        )
                    }

                    // Favorites section
                    if (favorites.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        item {
                            Text(
                                text = stringResource(R.string.favorites),
                                style = MaterialTheme.typography.titleSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(bottom = Spacing.sm),
                            )
                        }
                        items(favorites, key = { it.path }) { fav ->
                            FavoriteShortcutCard(
                                name = fav.name,
                                icon = fav.icon,
                                itemCount = fav.itemCount,
                                onClick = { onFavoriteClicked(fav) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FavoriteShortcutCard(
    name: String,
    icon: ImageVector,
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.md),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = Icons.Filled.Folder,
                contentDescription = null,
                modifier = Modifier.size(32.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Spacing.sm))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = stringResource(R.string.item_count, itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

@Composable
private fun StorageRootCard(
    name: String,
    storageInfo: String,
    itemCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val icon: ImageVector = when {
        name.contains("SD", ignoreCase = true) -> Icons.Filled.SdStorage
        name.contains("External", ignoreCase = true) -> Icons.Filled.SdStorage
        else -> Icons.Filled.Storage
    }

    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(Spacing.lg),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(Spacing.md))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Text(
                    text = storageInfo,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = stringResource(R.string.item_count, itemCount),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                )
            }
        }
    }
}

/**
 * Renders directory layers as a stack in a [Box].
 *
 * Only the top layer is visible and interactive. Older layers stay composed
 * (invisible, behind the top layer) so their LazyListState survives.
 *
 * Each layer has its own `rememberLazyListState()` — when the user goes back
 * and the old layer becomes top again, its scroll position is intact because
 * it was never removed from composition.
 */
@Composable
private fun DirectoryStackContent(
    layers: List<DirectoryLayer>,
    searchQuery: String,
    isSearchActive: Boolean,
    mediaMode: Boolean,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onFileClicked: (FolderItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        // Keep ALL layers in composition at the same nesting level.
        // This means every layer's rememberLazyListState survives forever,
        // no matter how deep the user navigates or how many times they go back.
        //
        // Only the top layer is visible + interactive; all others use alpha(0)
        // and noop callbacks so they're invisible and don't respond to touch.
        val noopAction: () -> Unit = {}
        val noopFolder: (FolderItem) -> Unit = {}
        val noopBreadcrumb: (String) -> Unit = {}

        // Cap at 32 layers to prevent unbounded memory growth on deep navigation.
        // Older layers drop off the bottom — Coil auto-cancels their in-flight thumb requests.
        val cappedLayers = layers.takeLast(32)
        val cappedTopIndex = cappedLayers.lastIndex
        cappedLayers.forEachIndexed { index, layer ->
            val isTop = index == cappedTopIndex
            key(layer.path) {
                DirectoryLayerView(
                    layer = layer,
                    searchQuery = if (isTop) searchQuery else "",
                    isSearchActive = if (isTop) isSearchActive else false,
                    mediaMode = mediaMode,
                    onFolderClicked = if (isTop) onFolderClicked else noopFolder,
                    onBreadcrumbClicked = if (isTop) onBreadcrumbClicked else noopBreadcrumb,
                    onRetry = if (isTop) onRetry else noopAction,
                    onRefresh = if (isTop) onRefresh else noopAction,
                    onFileClicked = if (isTop) onFileClicked else noopFolder,
                    modifier = (if (isTop) Modifier else Modifier.alpha(0f)).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun DirectoryLayerView(
    layer: DirectoryLayer,
    searchQuery: String,
    isSearchActive: Boolean,
    mediaMode: Boolean,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onFileClicked: (FolderItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val listState = rememberSaveable(saver = LazyListState.Saver) { LazyListState() }

    // Filter items: media mode shows only videos; normal mode hides documents.
    // ponytail: remember keyed on inputs — this runs for every one of the up to
    // 32 retained layers on each recomposition, so without caching it's O(32 × list).
    val visibleItems = remember(layer.items, mediaMode) {
        when {
            mediaMode -> layer.items.filter { it.isDirectory || it.isVideo() }
            else -> layer.items.filter { it.isDirectory || (!isDocumentExtension(it.name) && !isBinaryExtension(it.name)) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(
            breadcrumbs = layer.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        // Show whenever items exist. They persist across refresh (not cleared while
        // isLoading), so the count stays put instead of vanishing and reappearing.
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
            isEmpty = if (mediaMode) visibleItems.isEmpty() else layer.isEmpty,
            error = layer.error,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
            mediaMode = mediaMode,
            onItemClick = { data ->
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                if (data.isDirectory) onFolderClicked(item)
                else onFileClicked(item)
            },
            onRefresh = onRefresh,
            onRetry = onRetry,
            listState = listState,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

private fun FolderItem.toFileItemData() = FileItemData(
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

@PreviewLightDark
@Preview
@Composable
private fun FileBrowserScreenPreview() {
    HzPlayerTheme {
        FileBrowserScreen()
    }
}

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
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import com.rhnxdev.hzplayer.core.components.FileOptionsBottomSheet
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
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
import androidx.compose.ui.platform.LocalConfiguration
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
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
import com.rhnxdev.hzplayer.core.util.ArchiveBrowsePath
import com.rhnxdev.hzplayer.core.util.isArchiveExtension
import com.rhnxdev.hzplayer.core.util.isBinaryExtension
import com.rhnxdev.hzplayer.core.util.isDocumentExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import com.rhnxdev.hzplayer.presentation.browse.components.ArchivePasswordDialog

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAllVideos: (List<VideoItem>) -> Unit = {},
    onPlayAsAudio: (FolderItem) -> Unit = {},
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

    // A tapped real archive file opens as a virtual browsing layer (in-place);
    // everything else (incl. media entries whose path is an archive:// URI) plays.
    val handleFileClicked: (FolderItem) -> Unit = { item ->
        if (isArchiveExtension(item.name) && ArchiveBrowsePath.isRealFilePath(item.path)) {
            viewModel.onOpenArchive(item)
        } else {
            onFileClicked(item)
        }
    }

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
                    onToggleQuickAccess = viewModel::onToggleQuickAccess,
                    onRefresh = viewModel::onRefresh,
                )
                FileBrowserMode.BROWSING -> {
                    DirectoryStackContent(
                        layers = uiState.layers,
                        searchQuery = searchQuery,
                        isSearchActive = isSearchActive,
                        mediaMode = uiState.isMediaMode,
                        quickAccessPaths = uiState.quickAccessPaths,
                        onFolderClicked = viewModel::onFolderClicked,
                        onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                        onRetry = viewModel::onRetry,
                        onRefresh = viewModel::onRefresh,
                        getScrollState = viewModel::getScrollState,
                        saveScrollState = viewModel::saveScrollState,
                        fullScreenOverlay = fullScreenOverlay,
                        onFileClicked = handleFileClicked,
                        onPlayAsAudio = onPlayAsAudio,
                        onToggleFavorite = { viewModel.onToggleQuickAccess(it.path) },
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
    if (uiState.passwordPromptContainer != null) {
        ArchivePasswordDialog(
            archiveName = uiState.passwordPromptContainer!!.substringAfterLast('/'),
            onProvided = viewModel::onProvidePassword,
            onDismiss = viewModel::onCancelPasswordPrompt,
            error = uiState.passwordError
        )
    }
    if (uiState.solidArchiveWarningContainer != null) {
        com.rhnxdev.hzplayer.presentation.browse.components.SolidArchiveWarningDialog(
            archiveName = uiState.solidArchiveWarningContainer!!.name,
            onConfirm = { dontShowAgain -> viewModel.onConfirmSolidArchiveWarning(dontShowAgain) },
            onDismiss = viewModel::onDismissSolidArchiveWarning,
        )
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
    onToggleQuickAccess: (String) -> Unit,
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

                    // Quick Access section
                    if (favorites.isNotEmpty()) {
                        item {
                            Spacer(modifier = Modifier.height(Spacing.sm))
                        }
                        item {
                            Text(
                                text = stringResource(R.string.quick_access),
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
                                onRemoveClick = { onToggleQuickAccess(fav.path) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)
@Composable
private fun FavoriteShortcutCard(
    name: String,
    icon: ImageVector,
    itemCount: Int,
    onClick: () -> Unit,
    onRemoveClick: (() -> Unit)? = null,
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
                imageVector = icon,
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
            if (onRemoveClick != null) {
                var showMenu by remember { mutableStateOf(false) }
                androidx.compose.material3.IconButton(
                    onClick = { showMenu = true },
                    modifier = Modifier.size(36.dp),
                ) {
                    Icon(
                        imageVector = Icons.Filled.MoreVert,
                        contentDescription = stringResource(R.string.media_overflow_cd),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(18.dp),
                    )
                }
                if (showMenu) {
                    FileOptionsBottomSheet(
                        name = name,
                        isDirectory = true,
                        isFavorite = true,
                        onFavoriteClick = onRemoveClick,
                        onDismissRequest = { showMenu = false },
                    )
                }
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
    quickAccessPaths: Set<String>,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    getScrollState: (String, Int) -> SavedScrollPosition,
    saveScrollState: (String, Int, Int, Int, Boolean) -> Unit,
    fullScreenOverlay: Boolean = false,
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAsAudio: (FolderItem) -> Unit = {},
    onToggleFavorite: (com.rhnxdev.hzplayer.core.components.FileItemData) -> Unit = {},
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
        val noopFavorite: (com.rhnxdev.hzplayer.core.components.FileItemData) -> Unit = {}

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
                    quickAccessPaths = quickAccessPaths,
                    onFolderClicked = if (isTop) onFolderClicked else noopFolder,
                    onBreadcrumbClicked = if (isTop) onBreadcrumbClicked else noopBreadcrumb,
                    onRetry = if (isTop) onRetry else noopAction,
                    onRefresh = if (isTop) onRefresh else noopAction,
                    getScrollState = getScrollState,
                    saveScrollState = saveScrollState,
                    fullScreenOverlay = fullScreenOverlay,
                    isTopLayer = isTop,
                    onFileClicked = if (isTop) onFileClicked else noopFolder,
                    onPlayAsAudio = if (isTop) onPlayAsAudio else noopFolder,
                    onToggleFavorite = if (isTop) onToggleFavorite else noopFavorite,
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
    onToggleFavorite: (com.rhnxdev.hzplayer.core.components.FileItemData) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val currentOrientation = LocalConfiguration.current.orientation

    // The saved position is keyed per (path, orientation). getScrollState returns the
    // entry for the current orientation, or converts from the other orientation by
    // reusing the same first-visible item + offset — so a rotation lands on the same
    // visual spot, and each orientation keeps its own scroll position (no clobbering).
    val saved = remember(layer.path, currentOrientation) { getScrollState(layer.path, currentOrientation) }
    val initialIndex = saved.index
    val initialOffset = saved.offset
    val listState = remember<LazyListState>(layer.path) {
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = initialOffset,
        )
    }

    // On rotation the LazyListState is reused (keyed only on layer.path), so the
    // constructor values don't apply again. LazyColumn re-lays out with the new
    // viewport height and may auto-scroll backward when the saved index is near the
    // end — it pulls back to fill the taller/shorter screen (end-clamping).
    // Fix: if "isAtEnd" was saved, jump to the absolute last item so LazyColumn
    // applies its natural end-anchor. Otherwise restore the exact saved index.
    androidx.compose.runtime.LaunchedEffect(currentOrientation, layer.path) {
        val s = getScrollState(layer.path, currentOrientation)
        val total = listState.layoutInfo.totalItemsCount
        if (s.isAtEnd && total > 0) {
            listState.scrollToItem(total - 1, 0)
        } else {
            listState.scrollToItem(s.index, s.offset)
        }
    }

    // Save the scroll position ONLY when a real user scroll gesture ends.
    // A layout-driven re-anchor (e.g. the nav bar/rail hiding when the video
    // overlay opens, or a forced rotation) changes firstVisibleItemIndex too,
    // but does NOT set isScrollInProgress — so it must never be persisted, or
    // the stored index drifts by however many extra rows become visible.
    // We keep the *real* offset + orientation so the same-orientation restore is exact.
    // drop(1): skip the spurious initial emission when listState is (re)created on a
    // rotation — otherwise it would overwrite the good portrait save with a
    // landscape (index, 0) entry and lose the portrait offset on the way back.
    androidx.compose.runtime.LaunchedEffect(listState, layer.path) {
        androidx.compose.runtime.snapshotFlow { listState.isScrollInProgress }
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

    // When the full-screen overlay (video/audio player) closes, the underlying
    // list may have been re-anchored by the viewport resize while it was hidden.
    // Snap the top layer back using the same end-aware logic as the rotation effect.
    androidx.compose.runtime.LaunchedEffect(fullScreenOverlay) {
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
                // Snapshot scroll position NOW — before navigation fires isFullScreen=true,
                // which hides the nav bar/rail, resizes the viewport, and causes the
                // LazyColumn to re-anchor. Keep the real offset + orientation so the
                // same-orientation restore returns to the exact position.
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
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                if (data.isDirectory) onFolderClicked(item)
                else onFileClicked(item)
            },
            onRefresh = onRefresh,
            onRetry = onRetry,
            onPlayAsAudio = { data ->
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                onPlayAsAudio(item)
            },
            quickAccessPaths = quickAccessPaths,
            onToggleFavorite = onToggleFavorite,
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

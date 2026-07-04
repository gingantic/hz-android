package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
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
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onFileClicked: (FolderItem) -> Unit = {},
    fullScreenOverlay: Boolean = false,
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
    ) {
        when (uiState.mode) {
            FileBrowserMode.ROOTS -> StorageRootsContent(
                roots = uiState.roots,
                isLoading = uiState.isLoading,
                onRootClicked = viewModel::onStorageRootClicked,
                onRefresh = viewModel::onRefresh,
            )
            FileBrowserMode.BROWSING -> DirectoryStackContent(
                layers = uiState.layers,
                searchQuery = searchQuery,
                isSearchActive = isSearchActive,
                onFolderClicked = viewModel::onFolderClicked,
                onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                onRetry = viewModel::onRetry,
                onRefresh = viewModel::onRefresh,
                onFileClicked = onFileClicked,
            )
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun StorageRootsContent(
    roots: List<FolderItem>,
    isLoading: Boolean,
    onRootClicked: (FolderItem) -> Unit,
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
                        title = "No storage found",
                        subtitle = "Could not detect any storage volumes.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {
                LazyColumn(
                    modifier = Modifier.fillMaxSize().padding(Spacing.lg),
                    verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    item {
                        Text(
                            text = "Select storage",
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
                            storageInfo = "${freeStr} free of ${totalStr}",
                            itemCount = root.childCount,
                            onClick = { onRootClicked(root) },
                        )
                    }
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
                    text = "$itemCount items",
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
        val topIndex = layers.lastIndex
        val noopAction: () -> Unit = {}
        val noopFolder: (FolderItem) -> Unit = {}
        val noopBreadcrumb: (String) -> Unit = {}

        layers.forEachIndexed { index, layer ->
            val isTop = index == topIndex
            key(layer.path) {
                DirectoryLayerView(
                    layer = layer,
                    searchQuery = if (isTop) searchQuery else "",
                    isSearchActive = if (isTop) isSearchActive else false,
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
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onFileClicked: (FolderItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Each layer gets its own rememberLazyListState — stays alive as long
    // as this composable is in the tree (i.e. until popped from layers).
    val listState = rememberLazyListState()

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(
            breadcrumbs = layer.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        DirectoryBrowsePane(
            items = layer.items.map { it.toFileItemData() },
            isLoading = layer.isLoading,
            isEmpty = layer.isEmpty,
            error = layer.error,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
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
    id = id,
    name = name,
    path = path,
    isDirectory = isDirectory,
    fileSize = fileSize,
    childCount = childCount,
    mimeType = mimeType,
)

@PreviewLightDark
@Preview
@Composable
private fun FileBrowserScreenPreview() {
    HzPlayerTheme {
        FileBrowserScreen()
    }
}

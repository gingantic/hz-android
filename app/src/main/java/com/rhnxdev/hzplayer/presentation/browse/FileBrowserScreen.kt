package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.presentation.browse.components.FileListItem
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
        if (uiState.mode == FileBrowserMode.BROWSING) viewModel::onNavigateUp else null

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
            FileBrowserMode.BROWSING -> DirectoryContent(
                uiState = uiState,
                searchQuery = searchQuery,
                isSearchActive = isSearchActive,
                onFolderClicked = viewModel::onFolderClicked,
                onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                onRetry = viewModel::onRetry,
                onRefresh = viewModel::onRefresh,
                onBackToRoots = viewModel::onBackToRoots,
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DirectoryContent(
    uiState: FileBrowserUiState,
    searchQuery: String,
    isSearchActive: Boolean,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onBackToRoots: () -> Unit,
    onFileClicked: (FolderItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val displayItems = if (!isSearchActive || searchQuery.isBlank()) uiState.items
        else uiState.items.filter { it.name.contains(searchQuery, ignoreCase = true) }

    Column(modifier = modifier.fillMaxSize()) {
        // Breadcrumb bar
        BreadcrumbBar(
            breadcrumbs = uiState.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        // Content
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            val pullState = rememberPullToRefreshState()
            PullToRefreshBox(
                isRefreshing = uiState.isLoading,
                onRefresh = onRefresh,
                state = pullState,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.error != null -> {
                        Box(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            MediaErrorState(
                                title = "Could not load files",
                                subtitle = uiState.error ?: "",
                                onRetry = onRetry,
                            )
                        }
                    }
                    uiState.isEmpty -> {
                        Box(
                            modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            MediaEmptyState(
                                icon = Icons.Filled.Folder,
                                title = "This folder is empty",
                                subtitle = "Nothing to show here.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }
                    else -> {
                        if (displayItems.isEmpty() && isSearchActive) {
                            Box(
                                modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()),
                                contentAlignment = Alignment.Center
                            ) {
                                MediaEmptyState(
                                    icon = Icons.Filled.Search,
                                    title = "No results for \"$searchQuery\"",
                                    subtitle = "Try a different search term.",
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                        } else {
                            LazyColumn(
                                modifier = Modifier.fillMaxSize(),
                                contentPadding = PaddingValues(horizontal = Spacing.lg),
                                verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                            ) {
                                items(displayItems, key = { it.id }) { item ->
                                    FileListItem(
                                        item = item,
                                        onClick = {
                                            if (item.isDirectory) onFolderClicked(item)
                                            else onFileClicked(item)
                                        },
                                    )
                                }
                            }
                        }
                    }
                }
            } // PullToRefreshBox
        }
    }
}

@Composable
private fun BreadcrumbBar(
    breadcrumbs: List<Breadcrumb>,
    onBreadcrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex

            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (!isLast) Modifier.clickable { onBreadcrumbClicked(crumb.path) }
                else Modifier,
            )

            if (!isLast) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(16.dp).height(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun FileBrowserScreenPreview() {
    HzPlayerTheme {
        FileBrowserScreen()
    }
}

package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
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
import androidx.activity.compose.BackHandler
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Intercept back button when browsing directories
    BackHandler(enabled = uiState.mode == FileBrowserMode.BROWSING) {
        viewModel.onNavigateUp()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        // Toolbar
        HzPlayerTopBar(
            title = if (uiState.mode == FileBrowserMode.ROOTS) "Browse" else "Files",
        )

        when (uiState.mode) {
            FileBrowserMode.ROOTS -> StorageRootsContent(
                roots = uiState.roots,
                isLoading = uiState.isLoading,
                onRootClicked = viewModel::onStorageRootClicked,
            )
            FileBrowserMode.BROWSING -> DirectoryContent(
                uiState = uiState,
                onFolderClicked = viewModel::onFolderClicked,
                onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                onRetry = viewModel::onRetry,
                onBackToRoots = viewModel::onBackToRoots,
            )
        }
    }
}

@Composable
private fun StorageRootsContent(
    roots: List<FolderItem>,
    isLoading: Boolean,
    onRootClicked: (FolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        isLoading -> {
            MediaLoadingState(itemCount = 3, shape = ShimmerShape.STORAGE_ROOT, modifier = modifier.fillMaxSize())
        }
        roots.isEmpty() -> {
            MediaEmptyState(
                icon = Icons.Filled.Storage,
                title = "No storage found",
                subtitle = "Could not detect any storage volumes.",
                modifier = modifier.fillMaxSize(),
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxSize().padding(Spacing.lg),
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

@Composable
private fun DirectoryContent(
    uiState: FileBrowserUiState,
    onFolderClicked: (FolderItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onBackToRoots: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Breadcrumb bar
        BreadcrumbBar(
            breadcrumbs = uiState.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        // Content
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            when {
                uiState.isLoading -> {
                    MediaLoadingState(
                        itemCount = 6,
                        shape = ShimmerShape.FILE_LIST_ITEM,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                uiState.error != null -> {
                    MediaErrorState(
                        title = "Could not load files",
                        subtitle = uiState.error ?: "",
                        onRetry = onRetry,
                    )
                }
                uiState.isEmpty -> {
                    MediaEmptyState(
                        icon = Icons.Filled.Folder,
                        title = "This folder is empty",
                        subtitle = "Nothing to show here.",
                        modifier = Modifier.fillMaxSize(),
                    )
                }
                else -> {
                    LazyColumn(
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                    ) {
                        items(uiState.items, key = { it.id }) { item ->
                            FileListItem(
                                item = item,
                                onClick = { onFolderClicked(item) },
                            )
                        }
                    }
                }
            }
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

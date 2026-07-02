package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.SheetValue
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.unit.Velocity
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarPaddingValues
import com.rhnxdev.hzplayer.core.util.formatFileSize
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.presentation.player.SubtitleBreadcrumb
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserMode
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserUiState
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleFileBrowserBottomSheet(
    videoUri: String?,
    onDismiss: () -> Unit,
    onSubtitleSelected: (Uri, String) -> Unit,
    viewModel: SubtitleBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(videoUri) {
        viewModel.initVideoUri(videoUri)
    }

    // Intercept back button when not in ROOTS mode
    BackHandler(enabled = uiState.mode != SubtitleBrowserMode.ROOTS) {
        viewModel.onNavigateUp()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            // Prevent swipe-to-dismiss drag gesture by rejecting the Hidden target state
            targetValue != SheetValue.Hidden
        }
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = Color(0xFF1E1E24),
        tonalElevation = 0.dp,
        dragHandle = {},
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .fillMaxHeight(0.85f)
        ) {
            // Header — context-aware
            val isBrowsing = uiState.mode != SubtitleBrowserMode.ROOTS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(start = if (isBrowsing) 4.dp else 24.dp, end = 8.dp, top = 8.dp, bottom = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f),
                ) {
                    if (isBrowsing) {
                        // Back arrow — navigates up one level
                        IconButton(onClick = { viewModel.onNavigateUp() }) {
                            Icon(
                                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                                contentDescription = "Navigate up",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                    } else {
                        Icon(
                            imageVector = Icons.Default.Subtitles,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(22.dp),
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                    }
                    Text(
                        text = if (isBrowsing) {
                            uiState.currentPath.substringAfterLast('/').ifEmpty { uiState.currentPath }
                        } else {
                            "Browse Subtitles"
                        },
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = Color.White,
                        ),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    // "Storages" shortcut — visible only while browsing
                    if (isBrowsing) {
                        TextButton(onClick = { viewModel.loadLocalRoots() }) {
                            Icon(
                                imageVector = Icons.Default.Storage,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.primary,
                                modifier = Modifier.size(16.dp),
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "Storages",
                                color = MaterialTheme.colorScheme.primary,
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                    }
                    TextButton(onClick = onDismiss) {
                        Text(text = "Close", color = Color.White.copy(alpha = 0.7f))
                    }
                }
            }

            // Breadcrumb bar (only in browsing modes)
            if (uiState.mode == SubtitleBrowserMode.BROWSING_LOCAL) {
                BreadcrumbBar(
                    breadcrumbs = uiState.localBreadcrumbs,
                    onBreadcrumbClicked = viewModel::onLocalBreadcrumbClicked,
                )
            } else if (uiState.mode == SubtitleBrowserMode.BROWSING_REMOTE) {
                BreadcrumbBar(
                    breadcrumbs = uiState.remoteBreadcrumbs,
                    onBreadcrumbClicked = viewModel::onRemoteBreadcrumbClicked,
                )
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Main Content Area
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                when {
                    uiState.isLoading -> {
                        Box(
                            modifier = Modifier.fillMaxSize(),
                            contentAlignment = Alignment.Center
                        ) {
                            CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                        }
                    }
                    uiState.error != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center
                        ) {
                            MediaErrorState(
                                title = "Could not load files",
                                subtitle = uiState.error ?: "",
                                onRetry = {
                                    if (uiState.mode == SubtitleBrowserMode.BROWSING_LOCAL) {
                                        viewModel.onLocalBreadcrumbClicked(uiState.currentPath)
                                    } else if (uiState.mode == SubtitleBrowserMode.BROWSING_REMOTE) {
                                        viewModel.onRemoteBreadcrumbClicked(uiState.currentPath)
                                    } else {
                                        viewModel.loadLocalRoots()
                                    }
                                }
                            )
                        }
                    }
                    uiState.mode == SubtitleBrowserMode.ROOTS -> {
                        RootsContent(
                            uiState = uiState,
                            onLocalRootClicked = viewModel::onStorageRootClicked,
                            onRemoteRootClicked = {
                                val server = uiState.remoteServer
                                val parent = uiState.remoteParentPath
                                if (server != null && parent != null) {
                                    viewModel.browseRemoteDirectory(server, parent)
                                }
                            }
                        )
                    }
                    uiState.mode == SubtitleBrowserMode.BROWSING_LOCAL -> {
                        LocalBrowseContent(
                            items = uiState.localItems,
                            isEmpty = uiState.isEmpty,
                            onFolderClicked = viewModel::onLocalFolderClicked,
                            onFileClicked = { file ->
                                val fileUri = Uri.fromFile(File(file.path))
                                onSubtitleSelected(fileUri, file.name)
                            }
                        )
                    }
                    uiState.mode == SubtitleBrowserMode.BROWSING_REMOTE -> {
                        RemoteBrowseContent(
                            items = uiState.remoteItems,
                            isEmpty = uiState.isEmpty,
                            onFolderClicked = viewModel::onRemoteFolderClicked,
                            onFileClicked = { file ->
                                val fileUri = viewModel.buildRemotePlaybackUri(file.path)
                                if (fileUri != null) {
                                    onSubtitleSelected(fileUri, file.name)
                                }
                            }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun BreadcrumbBar(
    breadcrumbs: List<SubtitleBreadcrumb>,
    onBreadcrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex

            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) MaterialTheme.colorScheme.primary
                else Color.White.copy(alpha = 0.6f),
                modifier = if (!isLast) Modifier.clickable { onBreadcrumbClicked(crumb.path) }
                else Modifier,
            )

            if (!isLast) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .size(16.dp),
                    tint = Color.White.copy(alpha = 0.3f),
                )
            }
        }
    }
}

@Composable
private fun RootsContent(
    uiState: SubtitleBrowserUiState,
    onLocalRootClicked: (FolderItem) -> Unit,
    onRemoteRootClicked: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val nestedScrollConnection = rememberNoDragNestedScrollConnection()
    LazyColumn(
        modifier = modifier
            .fillMaxSize()
            .nestedScroll(nestedScrollConnection)
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        contentPadding = PaddingValues(vertical = 12.dp),
    ) {
        item {
            Text(
                text = "Select storage",
                style = MaterialTheme.typography.titleSmall,
                color = Color.White.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(uiState.localRoots) { root ->
            val isSdCard = root.name.contains("SD", ignoreCase = true) || root.name.contains("External", ignoreCase = true)
            StorageRootItem(
                name = root.name,
                path = "Local Storage",
                icon = if (isSdCard) Icons.Default.SdStorage else Icons.Default.Storage,
                onClick = { onLocalRootClicked(root) }
            )
        }

        if (uiState.remoteServer != null && uiState.remoteParentPath != null) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = "Network path",
                    style = MaterialTheme.typography.titleSmall,
                    color = Color.White.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            item {
                StorageRootItem(
                    name = uiState.remoteServer.name,
                    path = uiState.remoteParentPath,
                    icon = Icons.Default.Cloud,
                    onClick = onRemoteRootClicked
                )
            }
        }
    }
}

@Composable
private fun StorageRootItem(
    name: String,
    path: String,
    icon: ImageVector,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.05f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                modifier = Modifier.size(36.dp),
                tint = MaterialTheme.colorScheme.primary,
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    ),
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun LocalBrowseContent(
    items: List<FolderItem>,
    isEmpty: Boolean,
    onFolderClicked: (FolderItem) -> Unit,
    onFileClicked: (FolderItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isEmpty) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            MediaEmptyState(
                icon = Icons.Filled.Folder,
                title = "No subtitle files found",
                subtitle = "Folders and subtitles will show up here.",
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val nestedScrollConnection = rememberNoDragNestedScrollConnection()
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.id }) { item ->
                FileBrowserItem(
                    name = item.name,
                    subtitle = if (item.isDirectory) "${item.childCount} items" else formatFileSize(item.fileSize),
                    isDirectory = item.isDirectory,
                    onClick = {
                        if (item.isDirectory) onFolderClicked(item)
                        else onFileClicked(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun RemoteBrowseContent(
    items: List<RemoteFileItem>,
    isEmpty: Boolean,
    onFolderClicked: (RemoteFileItem) -> Unit,
    onFileClicked: (RemoteFileItem) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (isEmpty) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            MediaEmptyState(
                icon = Icons.Filled.Folder,
                title = "No subtitle files found",
                subtitle = "Folders and subtitles will show up here.",
                modifier = Modifier.fillMaxSize(),
            )
        }
    } else {
        val nestedScrollConnection = rememberNoDragNestedScrollConnection()
        LazyColumn(
            modifier = modifier
                .fillMaxSize()
                .nestedScroll(nestedScrollConnection),
            contentPadding = PaddingValues(horizontal = 24.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            items(items, key = { it.path }) { item ->
                FileBrowserItem(
                    name = item.name,
                    subtitle = if (item.isDirectory) "Folder" else formatFileSize(item.fileSize),
                    isDirectory = item.isDirectory,
                    onClick = {
                        if (item.isDirectory) onFolderClicked(item)
                        else onFileClicked(item)
                    }
                )
            }
        }
    }
}

@Composable
private fun FileBrowserItem(
    name: String,
    subtitle: String,
    isDirectory: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(10.dp),
        colors = CardDefaults.cardColors(
            containerColor = Color.White.copy(alpha = 0.03f),
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(
                imageVector = if (isDirectory) Icons.Default.Folder else Icons.Default.Description,
                contentDescription = null,
                modifier = Modifier.size(24.dp),
                tint = if (isDirectory) MaterialTheme.colorScheme.primary else Color.White.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = Color.White,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = Color.White.copy(alpha = 0.4f),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun rememberNoDragNestedScrollConnection(): NestedScrollConnection {
    return remember {
        object : NestedScrollConnection {
            override fun onPostScroll(
                consumed: Offset,
                unconsumed: Offset,
                source: NestedScrollSource
            ): Offset {
                return Offset(x = 0f, y = unconsumed.y)
            }

            override suspend fun onPostFling(consumed: Velocity, unconsumed: Velocity): Velocity {
                return Velocity(x = 0f, y = unconsumed.y)
            }
        }
    }
}

package com.rhnxdev.hzplayer.presentation.player.components

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Cloud
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.SdStorage
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.util.formatFileSize
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserUiState

@Composable
internal fun RootsContent(
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
                text = stringResource(R.string.select_storage),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                modifier = Modifier.padding(bottom = 4.dp),
            )
        }
        items(uiState.localRoots) { root ->
            val isSdCard = root.name.contains("SD", ignoreCase = true) || root.name.contains("External", ignoreCase = true)
            StorageRootItem(
                name = root.name,
                path = stringResource(R.string.local_storage),
                icon = if (isSdCard) Icons.Default.SdStorage else Icons.Default.Storage,
                onClick = { onLocalRootClicked(root) },
            )
        }
        if (uiState.remoteServer != null && uiState.remoteParentPath != null) {
            item {
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.network_path),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    modifier = Modifier.padding(bottom = 4.dp),
                )
            }
            item {
                StorageRootItem(
                    name = uiState.remoteServer.name,
                    path = uiState.remoteParentPath,
                    icon = Icons.Default.Cloud,
                    onClick = onRemoteRootClicked,
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
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.05f),
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
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
internal fun LocalBrowseContent(
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
                title = stringResource(R.string.no_subtitle_files),
                subtitle = stringResource(R.string.subtitles_empty_hint),
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
                    subtitle = if (item.isDirectory) stringResource(R.string.items_count, item.childCount) else formatFileSize(item.fileSize),
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
internal fun RemoteBrowseContent(
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
                title = stringResource(R.string.no_subtitle_files),
                subtitle = stringResource(R.string.subtitles_empty_hint),
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
                    subtitle = if (item.isDirectory) {
                        if (item.subfolderCount >= 0 && item.fileCount >= 0) {
                            stringResource(R.string.folder_badge, item.subfolderCount, item.fileCount)
                        } else if (item.childCount >= 0) {
                            stringResource(R.string.items_count, item.childCount)
                        } else {
                            stringResource(R.string.folder_label)
                        }
                    } else formatFileSize(item.fileSize),
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
            containerColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.03f),
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
                tint = if (isDirectory) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f),
            )
            Spacer(modifier = Modifier.width(14.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = name,
                    style = MaterialTheme.typography.bodyMedium.copy(
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.onSurface,
                    ),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
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

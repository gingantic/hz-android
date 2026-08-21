package com.rhnxdev.hzplayer.presentation.browse.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.CreateNewFolder
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.ViewSortBottomSheet
import com.rhnxdev.hzplayer.core.util.ArchiveBrowsePath
import com.rhnxdev.hzplayer.domain.model.FileMediaTypeFilter
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.presentation.browse.FileBrowserUiState

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FileBrowserTopBarActions(
    uiState: FileBrowserUiState,
    isSearchActive: Boolean,
    onToggleMediaMode: () -> Unit,
    onSortChanged: (SortType, SortDirection) -> Unit,
    onMediaTypeFilterChanged: (FileMediaTypeFilter) -> Unit,
    onRefresh: () -> Unit,
    onCreateFolder: (String) -> Unit,
) {
    if (isSearchActive) return

    var showViewSortSheet by remember { mutableStateOf(false) }
    var showMenu by remember { mutableStateOf(false) }
    var showNewFolderDialog by remember { mutableStateOf(false) }

    val isRealFolder = uiState.layers.isNotEmpty() &&
        ArchiveBrowsePath.isRealFilePath(uiState.layers.last().path)

    Row {
        // 1. View & Sort Action Button
        IconButton(onClick = { showViewSortSheet = true }) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Sort,
                contentDescription = stringResource(R.string.view_sort_cd),
            )
        }

        // 2. 3-Dot Overflow Menu
        Box {
            IconButton(onClick = { showMenu = true }) {
                Icon(
                    imageVector = Icons.Default.MoreVert,
                    contentDescription = stringResource(R.string.options_cd),
                )
            }

            MaterialTheme(
                shapes = MaterialTheme.shapes.copy(extraSmall = RoundedCornerShape(16.dp)),
            ) {
                DropdownMenu(
                    expanded = showMenu,
                    onDismissRequest = { showMenu = false },
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 0.dp,
                    shadowElevation = 6.dp,
                    border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.3f)),
                ) {
                    // New Folder
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.new_folder),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.CreateNewFolder,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        enabled = isRealFolder,
                        onClick = {
                            showMenu = false
                            showNewFolderDialog = true
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                            disabledTextColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f),
                            disabledLeadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.38f),
                        ),
                    )

                    // Refresh
                    DropdownMenuItem(
                        text = {
                            Text(
                                text = stringResource(R.string.refresh),
                                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Medium),
                            )
                        },
                        leadingIcon = {
                            Icon(
                                imageVector = Icons.Default.Refresh,
                                contentDescription = null,
                                modifier = Modifier.size(20.dp),
                            )
                        },
                        onClick = {
                            showMenu = false
                            onRefresh()
                        },
                        colors = MenuDefaults.itemColors(
                            textColor = MaterialTheme.colorScheme.onSurface,
                            leadingIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                        ),
                    )
                }
            }
        }
    }

    if (showViewSortSheet) {
        ViewSortBottomSheet(
            sortType = uiState.sortType,
            sortDirection = uiState.sortDirection,
            onSortChanged = onSortChanged,
            isMediaMode = uiState.isMediaMode,
            onToggleMediaMode = onToggleMediaMode,
            mediaTypeFilter = uiState.mediaTypeFilter,
            onMediaTypeFilterChanged = onMediaTypeFilterChanged,
            onDismissRequest = { showViewSortSheet = false },
        )
    }

    if (showNewFolderDialog) {
        NewFolderDialog(
            onConfirm = { name ->
                showNewFolderDialog = false
                onCreateFolder(name)
            },
            onDismiss = { showNewFolderDialog = false },
        )
    }
}

package com.rhnxdev.hzplayer.presentation.browse.components

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.presentation.browse.FileBrowserUiState

@Composable
fun FileBrowserTopBarActions(
    uiState: FileBrowserUiState,
    isSearchActive: Boolean,
    onToggleMediaMode: () -> Unit,
    onSortChanged: (SortType, SortDirection) -> Unit,
) {
    if (!isSearchActive) {
        IconButton(onClick = onToggleMediaMode) {
            Icon(
                imageVector = if (uiState.isMediaMode) Icons.AutoMirrored.Filled.ViewList
                else Icons.Filled.PhotoLibrary,
                contentDescription = if (uiState.isMediaMode) "List view" else "Media view",
            )
        }
    }
    var showSortMenu by remember { mutableStateOf(false) }
    IconButton(onClick = { showSortMenu = true }) {
        Icon(
            imageVector = Icons.AutoMirrored.Filled.Sort,
            contentDescription = stringResource(R.string.browse_sort_cd),
        )
    }
    DropdownMenu(
        expanded = showSortMenu,
        onDismissRequest = { showSortMenu = false },
    ) {
        val sortOptions = listOf(
            SortType.TITLE to stringResource(R.string.sort_by_name),
            SortType.DATE_MODIFIED to stringResource(R.string.sort_by_date),
            SortType.FILE_SIZE to stringResource(R.string.sort_by_size),
        )
        sortOptions.forEach { (type, label) ->
            DropdownMenuItem(
                text = { Text(label) },
                onClick = {
                    onSortChanged(type, uiState.sortDirection)
                    showSortMenu = false
                },
                leadingIcon = if (uiState.sortType == type) {
                    {
                        Icon(
                            imageVector = Icons.Filled.Check,
                            contentDescription = null,
                        )
                    }
                } else null,
            )
        }
        HorizontalDivider()
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_ascending)) },
            onClick = {
                onSortChanged(uiState.sortType, SortDirection.ASCENDING)
                showSortMenu = false
            },
            leadingIcon = if (uiState.sortDirection == SortDirection.ASCENDING) {
                { Icon(Icons.Filled.Check, null) }
            } else null,
        )
        DropdownMenuItem(
            text = { Text(stringResource(R.string.sort_descending)) },
            onClick = {
                onSortChanged(uiState.sortType, SortDirection.DESCENDING)
                showSortMenu = false
            },
            leadingIcon = if (uiState.sortDirection == SortDirection.DESCENDING) {
                { Icon(Icons.Filled.Check, null) }
            } else null,
        )
    }
}

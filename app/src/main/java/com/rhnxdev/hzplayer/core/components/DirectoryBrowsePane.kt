package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame

import androidx.compose.runtime.Immutable

/**
 * A generic file-item to be rendered in the [DirectoryBrowsePane].
 * Can be sourced from either local [FolderItem] or remote [RemoteFileItem].
 */
@Immutable
data class FileItemData(
    val id: String,
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val fileSize: Long = 0,
    val childCount: Int = 0,
    val subfolderCount: Int = -1,
    val fileCount: Int = -1,
    val mediaCount: Int = -1,
    val mimeType: String? = null,
    val dateModified: Long = 0,
    val durationMs: Long = 0,
    val playbackPositionMs: Long = 0,
    val playbackUri: String? = null,
    val resolution: String? = null,
    val dateAdded: Long = 0,
)

/**
 * Reusable directory browsing pane shared by local and remote file browsers.
 *
 * Handles the full state machine: loading -> empty -> error -> content,
 * wrapped in a [androidx.compose.material3.pulltorefresh.PullToRefreshBox].
 *
 * @param items        file items to display
 * @param isLoading    true while fetching directory contents
 * @param isEmpty      true when the directory has no contents
 * @param error        non-null when an error occurred
 * @param searchQuery  client-side search filter (empty = no filter)
 * @param isSearchActive  whether search mode is on
 * @param onItemClick  called when a file/directory is tapped
 * @param onRefresh    called on pull-to-refresh gesture
 * @param onRetry      called on "Retry" button from error state
 * @param listState    external LazyListState for scroll persistence
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DirectoryBrowsePane(
    items: List<FileItemData>,
    isLoading: Boolean,
    isEmpty: Boolean,
    error: String?,
    searchQuery: String,
    isSearchActive: Boolean,
    onItemClick: (FileItemData) -> Unit,
    onRefresh: () -> Unit,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
    mediaMode: Boolean = false,
    listState: androidx.compose.foundation.lazy.LazyListState? = null,
    contentPadding: PaddingValues = PaddingValues(horizontal = Spacing.lg),
    emptyTitle: String = "This folder is empty",
    emptySubtitle: String = "Nothing to show here.",
    noSearchResultsTitle: String = "No results",
    noSearchResultsSubtitle: String = "Try a different search term.",
) {
    val displayItems = remember(items, searchQuery, isSearchActive) {
        if (!isSearchActive || searchQuery.isBlank()) items
        else items.filter { it.name.contains(searchQuery, ignoreCase = true) }
    }

    // Initial loading (no items yet) — show shimmer directly.
    // Pull-to-refresh is only used for refreshing already-loaded content
    // to avoid showing two loading indicators at once.
    if (isLoading && items.isEmpty()) {
        MediaLoadingState(
            itemCount = 6,
            shape = ShimmerShape.FILE_LIST_ITEM,
            modifier = modifier.fillMaxSize(),
        )
        return
    }

    val pullState = rememberPullToRefreshState()
    PullToRefreshBox(
        isRefreshing = isLoading,
        onRefresh = onRefresh,
        state = pullState,
        modifier = modifier.fillMaxSize(),
    ) {
        when {
            error != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    MediaErrorState(
                        title = "Could not load files",
                        subtitle = error,
                        onRetry = onRetry,
                    )
                }
            }
            displayItems.isEmpty() && isSearchActive -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    MediaEmptyState(
                        icon = Icons.Filled.Search,
                        title = "$noSearchResultsTitle for \"$searchQuery\"",
                        subtitle = noSearchResultsSubtitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            isEmpty && !isSearchActive -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    contentAlignment = Alignment.Center,
                ) {
                    MediaEmptyState(
                        icon = Icons.Filled.Folder,
                        title = emptyTitle,
                        subtitle = emptySubtitle,
                        modifier = Modifier.fillMaxSize(),
                    )
                }
            }
            else -> {
                val actualListState = listState ?: androidx.compose.foundation.lazy.rememberLazyListState()
                LazyColumn(
                    state = actualListState,
                    modifier = Modifier.fillMaxSize(),
                    contentPadding = contentPadding,
                    verticalArrangement = Arrangement.spacedBy(2.dp),
                ) {
                    items(displayItems, key = { it.id }) { item ->
                        val onItemClicked = remember(item, onItemClick) { { onItemClick(item) } }
                        val thumbnail: @Composable (() -> Unit)? = if (mediaMode && !item.isDirectory) {
                            remember(item.playbackUri, item.path, item.dateModified, item.name) {
                                @Composable {
                                    AsyncImage(
                                        model = VideoFrame(item.playbackUri ?: item.path, item.dateModified),
                                        contentDescription = item.name,
                                        modifier = Modifier.fillMaxSize(),
                                        contentScale = ContentScale.Crop,
                                    )
                                }
                            }
                        } else null

                        FileItemCard(
                            name = item.name,
                            isDirectory = item.isDirectory,
                            fileSize = item.fileSize,
                            childCount = item.childCount,
                            subfolderCount = item.subfolderCount,
                            fileCount = item.fileCount,
                            mediaCount = item.mediaCount,
                            mediaMode = mediaMode,
                            mimeType = item.mimeType,
                            onClick = onItemClicked,
                            marqueeTitle = true,
                            durationMs = item.durationMs,
                            playbackPositionMs = item.playbackPositionMs,
                            leadingThumbnail = thumbnail,
                            resolution = if (mediaMode) item.resolution else null,
                            isNew = !item.isDirectory && item.dateAdded > 0L && (System.currentTimeMillis() / 1000L - item.dateAdded < 3600L),
                        )
                    }
                }
            }
        }
    }
}

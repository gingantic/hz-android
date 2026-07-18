package com.rhnxdev.hzplayer.presentation.video

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.components.FileItemCard
import com.rhnxdev.hzplayer.core.components.MediaCard
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.components.ViewToggleFab
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(
    viewModel: VideoLibraryViewModel = hiltViewModel(),
    onVideoClicked: (Long) -> Unit = {},
    isActive: Boolean = true,
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

    val folderTitle = uiState.selectedFolder?.let { key ->
        if (key == VideoLibraryViewModel.RECENT_KEY) {
            stringResource(R.string.cat_recent)
        } else {
            key
        }
    }

    var hasPermission by remember {
        mutableStateOf(com.rhnxdev.hzplayer.MainActivity.isMediaPermissionGranted(context))
    }

    val lifecycleOwner = androidx.compose.ui.platform.LocalLifecycleOwner.current
    androidx.compose.runtime.DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                val granted = com.rhnxdev.hzplayer.MainActivity.isMediaPermissionGranted(context)
                if (granted != hasPermission) {
                    hasPermission = granted
                    if (granted) {
                        viewModel.onRefresh()
                    }
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Refresh from source when this tab regains focus (skip the initial composition,
    // which already loaded via the ViewModel init).
    var firstComposition by remember { mutableStateOf(true) }
    LaunchedEffect(isActive) {
        if (firstComposition) {
            firstComposition = false
            return@LaunchedEffect
        }
        if (isActive && hasPermission) viewModel.onTabFocused()
    }

    Box(modifier = Modifier.fillMaxSize()) {
        HzPlayerSearchableScaffold(
            title = folderTitle ?: stringResource(R.string.app_name),
            onNavigateUp = if (uiState.selectedFolder != null && !isSearchActive) ({ viewModel.onNavigateUp() }) else null,
            isSearchActive = isSearchActive,
            searchQuery = searchQuery,
            onSearchToggle = viewModel::onSearchToggle,
            onSearchQueryChanged = viewModel::onSearchQueryChanged,
            onClearSearch = viewModel::onClearSearch,
            searchPlaceholder = stringResource(R.string.search_videos_placeholder),
            actions = {
                var showSortMenu by remember { mutableStateOf(false) }
                Box {
                    IconButton(onClick = { showSortMenu = true }) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = stringResource(R.string.video_sort_cd),
                        )
                    }
                    DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_by_title)) },
                            onClick = {
                                viewModel.onSortChanged(SortType.TITLE, uiState.sortDirection)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortType == SortType.TITLE) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_by_date)) },
                            onClick = {
                                viewModel.onSortChanged(SortType.DATE_ADDED, uiState.sortDirection)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortType == SortType.DATE_ADDED) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_by_duration)) },
                            onClick = {
                                viewModel.onSortChanged(SortType.DURATION, uiState.sortDirection)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortType == SortType.DURATION) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null,
                        )
                        HorizontalDivider()
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_ascending)) },
                            onClick = {
                                viewModel.onSortChanged(uiState.sortType, SortDirection.ASCENDING)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortDirection == SortDirection.ASCENDING) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null,
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_descending)) },
                            onClick = {
                                viewModel.onSortChanged(uiState.sortType, SortDirection.DESCENDING)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortDirection == SortDirection.DESCENDING) {
                                { Icon(Icons.Filled.Check, null) }
                            } else null,
                        )
                    }
                }
            },
        ) {
            // Content
            if (!hasPermission) {
                com.rhnxdev.hzplayer.core.components.PermissionRequiredState(
                    onGrantClick = {
                        val activity = context as? com.rhnxdev.hzplayer.MainActivity
                        activity?.requestMediaPermissions()
                    },
                    onSettingsClick = {
                        com.rhnxdev.hzplayer.MainActivity.openAppSettings(context)
                    },
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                val pullState = rememberPullToRefreshState()
                PullToRefreshBox(
                    isRefreshing = uiState.isLoading,
                    onRefresh = viewModel::onRefresh,
                    state = pullState,
                    modifier = Modifier.weight(1f).fillMaxSize(),
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        when {
                            uiState.isLoading && uiState.allVideos.isEmpty() -> {
                                MediaLoadingState(
                                    itemCount = 3,
                                    shape = if (uiState.viewMode == ViewMode.GRID) ShimmerShape.VIDEO_CATEGORY else ShimmerShape.LIST_ITEM,
                                    modifier = Modifier.fillMaxSize(),
                                )
                            }
                            uiState.error != null -> {
                                MediaErrorState(
                                    title = stringResource(R.string.video_load_error),
                                    subtitle = uiState.error ?: "",
                                    onRetry = viewModel::onRetry,
                                )
                            }
                            uiState.isEmpty && !isSearchActive -> {
                                MediaEmptyState(
                                    icon = Icons.Filled.VideoLibrary,
                                    title = stringResource(R.string.video_empty_title),
                                    subtitle = stringResource(R.string.video_empty_subtitle),
                                )
                            }
                            isSearchActive -> {
                                SearchResultsContent(
                                    videos = uiState.filteredVideos,
                                    viewMode = uiState.viewMode,
                                    onVideoClicked = { v ->
                                        viewModel.onVideoClicked(v)
                                        onVideoClicked(v.id)
                                    },
                                    searchQuery = searchQuery,
                                )
                            }
                            uiState.selectedFolder != null -> {
                                val drillVideos = remember(uiState.selectedFolder, uiState.categories, uiState.recentVideos) {
                                    when (uiState.selectedFolder) {
                                        VideoLibraryViewModel.RECENT_KEY -> uiState.recentVideos
                                        else -> uiState.categories.firstOrNull { it.title == uiState.selectedFolder }?.videos ?: emptyList()
                                    }
                                }
                                SearchResultsContent(
                                    videos = drillVideos,
                                    viewMode = uiState.viewMode,
                                    onVideoClicked = { v ->
                                        viewModel.onVideoClicked(v)
                                        onVideoClicked(v.id)
                                    },
                                    searchQuery = "",
                                )
                            }
                            else -> {
                                if (uiState.viewMode == ViewMode.GRID) {
                                    FolderGridContent(
                                        categories = uiState.categories,
                                        onFolderClicked = viewModel::onFolderClicked,
                                    )
                                } else {
                                    FolderListContent(
                                        categories = uiState.categories,
                                        onFolderClicked = viewModel::onFolderClicked,
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        // FAB
        if (hasPermission) {
            ViewToggleFab(
                currentView = uiState.viewMode,
                onToggle = viewModel::onViewModeChanged,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.lg),
            )
        }
    }
}

@Composable
private fun FolderListContent(
    categories: List<VideoCategory>,
    onFolderClicked: (String) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(categories, key = { it.title }) { category ->
            val firstVideo = category.videos.firstOrNull()
            FileItemCard(
                name = if (category.isRecent) stringResource(R.string.cat_recent) else category.title,
                isDirectory = true,
                fileSize = 0,
                childCount = category.videos.size,
                subfolderCount = 0,
                fileCount = category.videos.size,
                mediaCount = category.videos.size,
                mediaMode = true,
                leadingThumbnail = firstVideo?.let { v ->
                    {
                        SubcomposeAsyncImage(
                            model = VideoFrame(v.uri, v.dateModified),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.FOLDER)
                            },
                            loading = {
                                ThumbnailPlaceholder(mediaType = MediaType.FOLDER)
                            }
                        )
                    }
                },
                onClick = { onFolderClicked(if (category.isRecent) VideoLibraryViewModel.RECENT_KEY else category.title) },
            )
        }
    }
}

@Composable
private fun FolderGridContent(
    categories: List<VideoCategory>,
    onFolderClicked: (String) -> Unit,
) {
    LazyVerticalGrid(
        columns = GridCells.Fixed(2),
        contentPadding = PaddingValues(Spacing.sm),
        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        items(categories, key = { it.title }) { category ->
            val firstVideo = category.videos.firstOrNull()
            MediaCard(
                title = if (category.isRecent) stringResource(R.string.cat_recent) else category.title,
                subtitle = stringResource(R.string.folder_videos_count, category.videos.size),
                durationMs = 0,
                thumbnailContent = {
                    if (firstVideo != null) {
                        SubcomposeAsyncImage(
                            model = VideoFrame(firstVideo.uri, firstVideo.dateModified),
                            contentDescription = null,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            },
                            loading = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            }
                        )
                    } else {
                        ThumbnailPlaceholder(mediaType = MediaType.FOLDER)
                    }
                },
                onClick = { onFolderClicked(if (category.isRecent) VideoLibraryViewModel.RECENT_KEY else category.title) },
            )
        }
    }
}

@Composable
private fun SearchResultsContent(
    videos: List<VideoItem>,
    viewMode: ViewMode,
    onVideoClicked: (VideoItem) -> Unit,
    searchQuery: String,
) {
    if (videos.isEmpty()) {
        MediaEmptyState(
            icon = Icons.Default.Search,
            title = stringResource(R.string.no_results_title),
            subtitle = stringResource(R.string.video_search_empty, searchQuery),
        )
    } else if (viewMode == ViewMode.GRID) {
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            contentPadding = PaddingValues(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(videos, key = { it.id }) { video ->
                MediaCard(
                    title = video.title,
                    subtitle = remember(video) { buildSubtitle(video) },
                    durationMs = video.durationMs,
                    thumbnailContent = {
                        SubcomposeAsyncImage(
                            model = VideoFrame(video.uri, video.dateModified),
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            },
                            loading = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            }
                        )
                    },
                    onClick = { onVideoClicked(video) },
                )
            }
        }
    } else {
        LazyColumn(
            contentPadding = PaddingValues(Spacing.lg),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(videos, key = { it.id }) { video ->
                MediaListItem(
                    title = video.title,
                    subtitle = remember(video) { buildSubtitle(video) },
                    thumbnailContent = {
                        SubcomposeAsyncImage(
                            model = VideoFrame(video.uri, video.dateModified),
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            },
                            loading = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            }
                        )
                    },
                    onClick = { onVideoClicked(video) },
                )
            }
        }
    }
}

private fun buildSubtitle(video: VideoItem): String {
    val parts = mutableListOf<String>()
    video.resolution?.let { parts.add(it) }
    if (video.durationMs > 0) {
        val totalMinutes = (video.durationMs / 60_000).toInt()
        val hours = totalMinutes / 60
        val minutes = totalMinutes % 60
        parts.add(if (hours > 0) "${hours}h${minutes}m" else "${minutes}m")
    }
    return parts.joinToString(" • ")
}

@PreviewLightDark
@Preview
@Composable
private fun VideoLibraryScreenPreview() {
    HzPlayerTheme {
        VideoLibraryScreen()
    }
}

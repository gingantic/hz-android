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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
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
import com.rhnxdev.hzplayer.core.components.MediaCard
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.SortChipOption
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.components.ViewToggleFab
import com.rhnxdev.hzplayer.core.designsystem.Spacing
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
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

    Box(modifier = Modifier.fillMaxSize()) {
        HzPlayerSearchableScaffold(
            title = stringResource(R.string.app_name),
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
                            onClick = { viewModel.onSortChanged(SortType.TITLE); showSortMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_by_date)) },
                            onClick = { viewModel.onSortChanged(SortType.DATE_ADDED); showSortMenu = false },
                        )
                        DropdownMenuItem(
                            text = { Text(stringResource(R.string.sort_by_duration)) },
                            onClick = { viewModel.onSortChanged(SortType.DURATION); showSortMenu = false },
                        )
                    }
                }
            },
        ) {
            // Content
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
                        uiState.viewMode == ViewMode.GRID -> {
                            GridContent(
                                categories = uiState.categories,
                                onVideoClicked = { v ->
                                    viewModel.onVideoClicked(v)
                                    onVideoClicked(v.id)
                                },
                            )
                        }
                        else -> {
                            ListContent(
                                videos = uiState.allVideos,
                                onVideoClicked = { v ->
                                    viewModel.onVideoClicked(v)
                                    onVideoClicked(v.id)
                                },
                            )
                        }
                    }
                }
            }
        }

        // FAB
        ViewToggleFab(
            currentView = uiState.viewMode,
            onToggle = viewModel::onViewModeChanged,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(Spacing.lg),
        )
    }
}

@Composable
private fun GridContent(
    categories: List<VideoCategory>,
    onVideoClicked: (VideoItem) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(vertical = Spacing.sm),
    ) {
        items(categories, key = { it.title }) { category ->
            Column {
                Text(
                    text = if (category.isRecent) stringResource(R.string.cat_recent) else category.title,
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                LazyRow(
                    contentPadding = PaddingValues(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                ) {
                    items(category.videos, key = { it.id }) { video ->
                        MediaCard(
                            title = video.title,
                            subtitle = buildSubtitle(video),
                            durationMs = video.durationMs,
                            progress = video.watchedProgress.takeIf { it > 0f },
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
                            modifier = Modifier.width(200.dp),
                        )
                    }
                }
                Spacer(modifier = Modifier.height(Spacing.lg))
            }
        }
    }
}

@Composable
private fun ListContent(
    videos: List<VideoItem>,
    onVideoClicked: (VideoItem) -> Unit,
) {
    LazyColumn(
        contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        items(videos, key = { it.id }) { video ->
            MediaListItem(
                title = video.title,
                subtitle = buildSubtitle(video),
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
                    subtitle = buildSubtitle(video),
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
                    subtitle = buildSubtitle(video),
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

private val sortOptions = listOf(
    SortChipOption("Title", SortType.TITLE),
    SortChipOption("Date", SortType.DATE_ADDED),
    SortChipOption("Duration", SortType.DURATION),
)

@PreviewLightDark
@Preview
@Composable
private fun VideoLibraryScreenPreview() {
    HzPlayerTheme {
        VideoLibraryScreen()
    }
}

package com.rhnxdev.hzplayer.presentation.video

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.gestures.ScrollableState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.rememberLazyGridState
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.material3.pulltorefresh.rememberPullToRefreshState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.snapshotFlow
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
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.MediaPropertiesDialog
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.core.components.rowThumbnailFraction
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.components.ViewSortBottomSheet
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import androidx.compose.ui.layout.ContentScale
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.ThumbnailSize
import com.rhnxdev.hzplayer.domain.model.ViewMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import com.rhnxdev.hzplayer.presentation.browse.components.AllFilesAccessDialog
import com.rhnxdev.hzplayer.presentation.browse.components.DeleteConfirmDialog
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.core.util.formatFileSize

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoLibraryScreen(
    viewModel: VideoLibraryViewModel = hiltViewModel(),
    onVideoClicked: (VideoItem) -> Unit = {},
    onPlayVideoPlaylist: ((List<VideoItem>, Int) -> Unit)? = null,
    onPlayAsAudio: (VideoItem) -> Unit = {},
    onOpenHistory: () -> Unit = {},
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

    // True while a video list scrolls or sits at its end; hides the Play All FAB.
    var isListAtEndOrScrolling by remember { mutableStateOf(false) }

    // Play All plays what the list shows: search results, the open folder, or the whole library.
    val playableVideos = remember(
        isSearchActive,
        uiState.filteredVideos,
        uiState.selectedFolder,
        uiState.recentVideos,
        uiState.categories,
        uiState.allVideos,
    ) {
        when {
            isSearchActive -> uiState.filteredVideos
            uiState.selectedFolder == VideoLibraryViewModel.RECENT_KEY -> uiState.recentVideos
            uiState.selectedFolder != null ->
                uiState.categories.firstOrNull { it.title == uiState.selectedFolder }?.videos ?: emptyList()
            else -> uiState.allVideos
        }
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
                IconButton(onClick = onOpenHistory) {
                    Icon(
                        imageVector = Icons.Filled.History,
                        contentDescription = stringResource(R.string.history_open_cd),
                    )
                }
                var showViewSortSheet by remember { mutableStateOf(false) }
                IconButton(onClick = { showViewSortSheet = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.video_sort_cd),
                    )
                }
                if (showViewSortSheet) {
                    ViewSortBottomSheet(
                        sortType = uiState.sortType,
                        sortDirection = uiState.sortDirection,
                        onSortChanged = viewModel::onSortChanged,
                        availableSortTypes = listOf(
                            SortType.TITLE,
                            SortType.DATE_ADDED,
                            SortType.DURATION,
                        ),
                        thumbnailSize = uiState.thumbnailSize,
                        onThumbnailSizeChanged = viewModel::onThumbnailSizeChanged,
                        viewMode = uiState.viewMode,
                        onViewModeChanged = viewModel::onViewModeChanged,
                        onDismissRequest = { showViewSortSheet = false },
                    )
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
                                    thumbnailSize = uiState.thumbnailSize,
                                    onVideoClicked = onVideoClicked,
                                    onPlayAsAudio = onPlayAsAudio,
                                    onDeleteVideo = viewModel::onDeleteVideo,
                                    searchQuery = searchQuery,
                                    onListAtEndOrScrollingChanged = { isListAtEndOrScrolling = it },
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
                                    thumbnailSize = uiState.thumbnailSize,
                                    onVideoClicked = onVideoClicked,
                                    onPlayAsAudio = onPlayAsAudio,
                                    onDeleteVideo = viewModel::onDeleteVideo,
                                    searchQuery = "",
                                    onListAtEndOrScrollingChanged = { isListAtEndOrScrolling = it },
                                )
                            }
                            else -> {
                                if (uiState.viewMode == ViewMode.GRID) {
                                    FolderGridContent(
                                        categories = uiState.categories,
                                        thumbnailSize = uiState.thumbnailSize,
                                        onFolderClicked = viewModel::onFolderClicked,
                                        onListAtEndOrScrollingChanged = { isListAtEndOrScrolling = it },
                                    )
                                } else {
                                    FolderListContent(
                                        categories = uiState.categories,
                                        thumbnailSize = uiState.thumbnailSize,
                                        onFolderClicked = viewModel::onFolderClicked,
                                        onListAtEndOrScrollingChanged = { isListAtEndOrScrolling = it },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        uiState.deleteConfirmVideo?.let { video ->
            DeleteConfirmDialog(
                itemName = video.title,
                isDirectory = false,
                onConfirm = viewModel::onConfirmDelete,
                onDismiss = viewModel::onDismissDeleteConfirm,
            )
        }
        if (uiState.showAllFilesAccessPrompt) {
            AllFilesAccessDialog(
                onDismiss = viewModel::onDismissAllFilesAccessPrompt,
                messageRes = R.string.all_files_access_delete_message,
            )
        }

        // One-shot feedback for delete results.
        val snackbarHostState = remember { SnackbarHostState() }
        LaunchedEffect(uiState.deleteOutcome) {
            val message = when (val outcome = uiState.deleteOutcome ?: return@LaunchedEffect) {
                is DeleteOutcome.Deleted -> context.getString(R.string.video_deleted_message, outcome.title)
                DeleteOutcome.Failed -> context.getString(R.string.video_delete_failed)
            }
            snackbarHostState.showSnackbar(message)
            viewModel.onDeleteOutcomeShown()
        }

        // Undo grace period: the video is already hidden; the disk delete runs only after
        // the ViewModel's grace timer. This snackbar (Short = 4s) offers the undo.
        uiState.deleteGraceVideo?.let { graceVideo ->
            val message = stringResource(R.string.delete_grace_message, graceVideo.title)
            val undoLabel = stringResource(R.string.undo)
            LaunchedEffect(graceVideo.uri) {
                val result = snackbarHostState.showSnackbar(
                    message = message,
                    actionLabel = undoLabel,
                    duration = SnackbarDuration.Short,
                )
                if (result == SnackbarResult.ActionPerformed) {
                    viewModel.onUndoDelete()
                }
            }
        }

        SnackbarHost(
            hostState = snackbarHostState,
            modifier = Modifier
                .align(Alignment.BottomCenter)
                .padding(Spacing.lg),
        )

        // Play All FAB — hidden while a snackbar is up so it never covers the message or its Undo
        // action, while a list scrolls, and at a list's end where it would cover the last item.
        val isSnackbarShowing = snackbarHostState.currentSnackbarData != null
        if (hasPermission) {
            AnimatedVisibility(
                visible = playableVideos.isNotEmpty() && !isSnackbarShowing && !isListAtEndOrScrolling,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(Spacing.lg),
            ) {
                PlayAllFab(
                    onClick = { onPlayVideoPlaylist?.invoke(playableVideos, 0) },
                )
            }
        }
    }
}

/**
 * Reports when a list starts or stops scrolling, or reaches its end. The Play All FAB hides in
 * both cases: while the list moves, and at the end where it would cover the last item.
 * Sends false when the list leaves composition, so a list removed mid-scroll cannot leave the FAB hidden.
 * @param scrollState the list's state, a LazyListState or LazyGridState
 * @param onHidingChanged receives true while the list scrolls or sits at its end
 */
@Composable
private fun ReportListFabHiding(
    scrollState: ScrollableState,
    onHidingChanged: (Boolean) -> Unit,
) {
    val currentState by rememberUpdatedState(scrollState)
    val currentOnHidingChanged by rememberUpdatedState(onHidingChanged)
    LaunchedEffect(Unit) {
        snapshotFlow {
            currentState.isScrollInProgress ||
                (currentState.canScrollBackward && !currentState.canScrollForward)
        }.collect { currentOnHidingChanged(it) }
    }
    DisposableEffect(Unit) {
        onDispose { currentOnHidingChanged(false) }
    }
}

/** Play All FAB, styled like the file browser and network screens. */
@Composable
private fun PlayAllFab(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    FloatingActionButton(
        onClick = onClick,
        modifier = modifier,
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary,
        elevation = FloatingActionButtonDefaults.elevation(
            defaultElevation = 6.dp,
            pressedElevation = 12.dp,
        ),
    ) {
        Icon(
            imageVector = Icons.Filled.PlayArrow,
            contentDescription = stringResource(R.string.play_all_cd),
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun PlayAllFabPreview() {
    HzPlayerTheme {
        PlayAllFab(onClick = {})
    }
}

@Composable
private fun FolderListContent(
    categories: List<VideoCategory>,
    thumbnailSize: ThumbnailSize,
    onFolderClicked: (String) -> Unit,
    onListAtEndOrScrollingChanged: (Boolean) -> Unit,
) {
    val listState = rememberLazyListState()
    ReportListFabHiding(
        scrollState = listState,
        onHidingChanged = onListAtEndOrScrollingChanged,
    )
    LazyColumn(
        state = listState,
        contentPadding = PaddingValues(Spacing.sm),
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
                thumbnailFraction = thumbnailSize.rowThumbnailFraction,
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
    thumbnailSize: ThumbnailSize,
    onFolderClicked: (String) -> Unit,
    onListAtEndOrScrollingChanged: (Boolean) -> Unit,
) {
    val gridState = rememberLazyGridState()
    ReportListFabHiding(
        scrollState = gridState,
        onHidingChanged = onListAtEndOrScrollingChanged,
    )
    LazyVerticalGrid(
        state = gridState,
        columns = GridCells.Fixed(thumbnailSize.columns),
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

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun SearchResultsContent(
    videos: List<VideoItem>,
    viewMode: ViewMode,
    thumbnailSize: ThumbnailSize,
    onVideoClicked: (VideoItem) -> Unit,
    onPlayAsAudio: (VideoItem) -> Unit,
    onDeleteVideo: (VideoItem) -> Unit,
    searchQuery: String,
    onListAtEndOrScrollingChanged: (Boolean) -> Unit,
) {
    var propertiesVideo by remember { mutableStateOf<VideoItem?>(null) }

    propertiesVideo?.let { video ->
        MediaPropertiesDialog(
            title = video.title,
            properties = buildVideoProperties(video),
            onDismiss = { propertiesVideo = null },
            probeUri = video.uri,
            thumbnailContent = {
                SubcomposeAsyncImage(
                    model = VideoFrame(video.uri, video.dateModified),
                    contentDescription = video.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                )
            },
        )
    }

    if (videos.isEmpty()) {
        MediaEmptyState(
            icon = Icons.Default.Search,
            title = stringResource(R.string.no_results_title),
            subtitle = stringResource(R.string.video_search_empty, searchQuery),
        )
    } else if (viewMode == ViewMode.GRID) {
        val gridState = rememberLazyGridState()
        ReportListFabHiding(
            scrollState = gridState,
            onHidingChanged = onListAtEndOrScrollingChanged,
        )
        LazyVerticalGrid(
            state = gridState,
            columns = GridCells.Fixed(thumbnailSize.columns),
            contentPadding = PaddingValues(Spacing.sm),
            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.sm),
        ) {
            items(videos, key = { it.id }) { video ->
                MediaCard(
                    title = video.title,
                    subtitle = remember(video) { buildSubtitle(video) },
                    durationMs = video.durationMs,
                    progress = if (video.watchedProgress > 0f) video.watchedProgress else null,
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
                    onPropertiesClick = { propertiesVideo = video },
                    onPlayAsAudioClick = { onPlayAsAudio(video) },
                    onDeleteClick = { onDeleteVideo(video) },
                )
            }
        }
    } else {
        // Same row as the file browser, so list mode reads the same across tabs.
        val listState = rememberLazyListState()
        ReportListFabHiding(
            scrollState = listState,
            onHidingChanged = onListAtEndOrScrollingChanged,
        )
        LazyColumn(
            state = listState,
            contentPadding = PaddingValues(Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            items(videos, key = { it.id }) { video ->
                FileItemCard(
                    name = video.title,
                    isDirectory = false,
                    fileSize = video.fileSize,
                    childCount = 0,
                    mimeType = video.mimeType,
                    durationMs = video.durationMs,
                    playbackPositionMs = watchedPositionMs(video),
                    resolution = video.resolution,
                    mediaMode = true,
                    marqueeTitle = true,
                    thumbnailFraction = thumbnailSize.rowThumbnailFraction,
                    leadingThumbnail = {
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
                    onPropertiesClick = { propertiesVideo = video },
                    onPlayAsAudioClick = { onPlayAsAudio(video) },
                    onDeleteClick = { onDeleteVideo(video) },
                )
            }
        }
    }
}

/** [FileItemCard] draws watch progress from a position; the library only stores the fraction. */
private fun watchedPositionMs(video: VideoItem): Long =
    if (video.watchedProgress > 0f && video.durationMs > 0) {
        (video.watchedProgress * video.durationMs).toLong()
    } else 0L

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

@Composable
private fun buildVideoProperties(video: VideoItem): List<Pair<String, String>> {
    val dateFormat = remember { java.text.SimpleDateFormat("dd MMM yyyy, HH:mm", java.util.Locale.getDefault()) }
    val path = remember(video.uri) {
        android.net.Uri.parse(video.uri).path ?: video.uri
    }
    val fileName = remember(path) { path.substringAfterLast('/') }

    return buildList {
        add(stringResource(R.string.prop_file_name) to fileName)
        add(stringResource(R.string.prop_path) to path)
        if (video.fileSize > 0) {
            add(stringResource(R.string.prop_size) to formatFileSize(video.fileSize))
        }
        video.resolution?.let { add(stringResource(R.string.prop_resolution) to it) }
        if (video.durationMs > 0) {
            add(stringResource(R.string.prop_duration) to formatDuration(video.durationMs))
        }
        video.mimeType?.let { add(stringResource(R.string.prop_type) to it) }
        if (video.dateAdded > 0) {
            add(stringResource(R.string.prop_date_added) to dateFormat.format(java.util.Date(video.dateAdded * 1000)))
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun VideoLibraryScreenPreview() {
    HzPlayerTheme {
        VideoLibraryScreen()
    }
}

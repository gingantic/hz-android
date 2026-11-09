package com.rhnxdev.hzplayer.presentation.network

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.components.SelectionAction
import com.rhnxdev.hzplayer.core.components.SelectionActionBar
import com.rhnxdev.hzplayer.core.components.ViewSortBottomSheet
import com.rhnxdev.hzplayer.core.util.isVideoMedia
import com.rhnxdev.hzplayer.core.util.isVideoOrStreamDefault
import com.rhnxdev.hzplayer.core.util.withLiveCookies
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.network.components.CredentialDialog
import com.rhnxdev.hzplayer.presentation.network.components.NetworkHomeContent
import com.rhnxdev.hzplayer.presentation.network.components.ServerBrowseStackContent
import com.rhnxdev.hzplayer.presentation.network.components.ServerConfigDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onPlayStream: (url: String, title: String, isVideo: Boolean, mimeType: String?, headers: Map<String, String>) -> Unit = { _, _, _, _, _ -> },
    onPlayRemoteFile: (uri: String, title: String, isVideo: Boolean, mimeType: String?) -> Unit = { _, _, _, _ -> },
    onPlayAllVideos: (List<VideoItem>) -> Unit = {},
    onOpenBrowser: (String?) -> Unit = {},
    fullScreenOverlay: Boolean = false,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()
    val isSelectingRaw by viewModel.selection.isSelecting.collectAsStateWithLifecycle()
    val selectedKeys by viewModel.selection.selectedKeys.collectAsStateWithLifecycle()
    val allSelected by viewModel.selection.allListedSelected.collectAsStateWithLifecycle()
    val isSelecting = uiState.mode == NetworkScreenMode.SERVER_BROWSE && isSelectingRaw
    val selectionCount = if (isSelecting) selectedKeys.size else 0
    // Reported by ServerBrowseStackContent. Hides the selection bar at the list end, like the Play All FAB.
    var isListAtEnd by remember { mutableStateOf(false) }

    if (uiState.showServerDialog) {
        ServerConfigDialog(
            initialServer = uiState.editingServer,
            onSave = viewModel::onSaveServer,
            onDismiss = viewModel::onDismissServerDialog,
        )
    }

    uiState.discoveredServerCredential?.let { request ->
        CredentialDialog(
            server = request.server,
            error = request.error,
            onProvided = request.onProvided,
            onDismiss = viewModel::onDismissCredentialDialog,
        )
    }

    val onNavigateUp: (() -> Unit)? =
        if (uiState.mode == NetworkScreenMode.SERVER_BROWSE) {
            { viewModel.onRemoteNavigateUp() }
        } else null

    // One-shot feedback while and after a saved server's address is re-discovered.
    val snackbarHostState = remember { SnackbarHostState() }
    val searchingMessage = uiState.relocatingServerName?.let { stringResource(R.string.server_searching, it) }
    LaunchedEffect(searchingMessage) {
        // Indefinite: ends when the search finishes, because this effect restarts and cancels it.
        if (searchingMessage != null) {
            snackbarHostState.showSnackbar(searchingMessage, duration = SnackbarDuration.Indefinite)
        }
    }
    val notice = uiState.notice
    val noticeMessage = when (notice) {
        is NetworkNotice.ServerAddressUpdated -> stringResource(R.string.server_address_updated, notice.serverName)
        is NetworkNotice.ServerNotFound -> stringResource(R.string.server_not_found, notice.serverName)
        null -> null
    }
    LaunchedEffect(notice) {
        if (noticeMessage != null) {
            snackbarHostState.showSnackbar(noticeMessage)
            viewModel.onNoticeShown()
        }
    }

    HzPlayerSearchableScaffold(
        title = when (uiState.mode) {
            NetworkScreenMode.HOME -> "Network"
            NetworkScreenMode.SERVER_BROWSE -> uiState.browsingServer?.name ?: "Server"
        },
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        onSearchToggle = viewModel::onSearchToggle,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::onClearSearch,
        onNavigateUp = onNavigateUp,
        searchPlaceholder = "Search remote files...",
        fullScreenOverlay = fullScreenOverlay,
        isActive = isActive,
        isSelecting = isSelecting,
        selectionCount = selectionCount,
        onExitSelection = viewModel::onExitSelection,
        onToggleSelectAll = viewModel::onToggleSelectAll,
        allSelected = allSelected,
        actions = {
            if (uiState.mode == NetworkScreenMode.HOME) {
                IconButton(onClick = viewModel::onToggleHomeView) {
                    Icon(
                        imageVector = if (uiState.isHomeListView) Icons.Filled.ViewModule
                        else Icons.AutoMirrored.Filled.ViewList,
                        contentDescription = if (uiState.isHomeListView) "Card view" else "List view",
                    )
                }
            }
            if (uiState.mode == NetworkScreenMode.SERVER_BROWSE && !isSearchActive) {
                var showViewSortSheet by remember { mutableStateOf(false) }
                IconButton(onClick = { showViewSortSheet = true }) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.view_sort_cd),
                    )
                }
                if (showViewSortSheet) {
                    ViewSortBottomSheet(
                        sortType = uiState.sortType,
                        sortDirection = uiState.sortDirection,
                        onSortChanged = viewModel::onSortChanged,
                        isMediaMode = uiState.isMediaMode,
                        onToggleMediaMode = viewModel::onToggleMediaMode,
                        thumbnailSize = uiState.thumbnailSize,
                        onThumbnailSizeChanged = viewModel::onThumbnailSizeChanged,
                        availableSortTypes = listOf(
                            SortType.TITLE,
                            SortType.DATE_MODIFIED,
                            SortType.FILE_SIZE,
                        ),
                        onDismissRequest = { showViewSortSheet = false },
                    )
                }
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState.mode) {
                NetworkScreenMode.HOME -> NetworkHomeContent(
                    uiState = uiState,
                    onStreamUrlChanged = viewModel::onStreamUrlChanged,
                    onPlayStream = {
                        val url = viewModel.onPlayStream() ?: return@NetworkHomeContent
                        val title = url.substringAfterLast("/").ifEmpty { url }
                        scope.launch {
                            val res = viewModel.resolveStreamMedia(url)
                            onPlayStream(url, title, res.isVideo, res.mimeType, emptyMap())
                        }
                    },
                    onScanNetwork = viewModel::onScanNetwork,
                    onStopScan = viewModel::onStopScan,
                    onAddServer = viewModel::onAddServerClicked,
                    onBrowseServer = viewModel::onBrowseServer,
                    onEditServer = viewModel::onEditServer,
                    onDeleteServer = viewModel::onDeleteServer,
                    onDiscoveredServerTapped = viewModel::onDiscoveredServerTapped,
                    onSaveDiscoveredServer = viewModel::onSaveDiscoveredServer,
                    onDismissDiscoveredServer = viewModel::onDismissDiscoveredServer,
                    onPlayHistoryItem = { item ->
                        val url = viewModel.onPlayHistoryItem(item)
                        val pageUrl = item.pageUrl.orEmpty()
                        val headersMap = item.headersMap.withLiveCookies(targetUrl = url, refererUrl = pageUrl)
                        val mime = item.mimeType?.ifBlank { null }
                        onPlayStream(url, item.title, isVideoOrStreamDefault(url), mime, headersMap)
                    },
                    onToggleFavorite = viewModel::onToggleFavorite,
                    onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
                    onClearHistory = viewModel::onClearHistory,
                    onOpenBrowser = onOpenBrowser,
                )

                 NetworkScreenMode.SERVER_BROWSE -> ServerBrowseStackContent(
                    uiState = uiState,
                    searchQuery = searchQuery,
                    isSearchActive = isSearchActive,
                    onSearchToggle = viewModel::onSearchToggle,
                    onSearchQueryChanged = viewModel::onSearchQueryChanged,
                    onClearSearch = viewModel::onClearSearch,
                    onFolderClicked = { item -> viewModel.onRemoteFolderClicked(item) },
                    onFileClicked = { item ->
                        val uri = viewModel.buildPlaybackUri(item.path) ?: return@ServerBrowseStackContent
                        onPlayRemoteFile(uri, item.name, isVideoOrStreamDefault(item.name), item.mimeType)
                    },
                    onBreadcrumbClicked = viewModel::onRemoteBreadcrumbClicked,
                    onRetry = viewModel::onRetryBrowse,
                    onRefresh = viewModel::onRefreshBrowse,
                    buildPlaybackUri = { path -> viewModel.buildPlaybackUri(path) },
                    getScrollState = viewModel::getScrollState,
                    getScrollStateIsAtEnd = viewModel::getScrollStateIsAtEnd,
                    saveScrollState = viewModel::saveScrollState,
                    isListAtEnd = isListAtEnd,
                    onListAtEndChanged = { isListAtEnd = it },
                    fullScreenOverlay = fullScreenOverlay,
                    onPlayAllVideos = {
                        val playlist = viewModel.collectVideoPlaylist()
                        if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                    },
                    onPlayAsAudio = { item ->
                        val uri = viewModel.buildPlaybackUri(item.path) ?: item.path
                        onPlayRemoteFile(uri, item.name, false, item.mimeType)
                    },
                    selectionMode = isSelecting,
                    selectedKeys = selectedKeys,
                    onLongPressItem = viewModel::onLongPressItem,
                    onToggleSelect = viewModel::onToggleSelect,
                    onVisibleKeysChanged = viewModel::onVisibleKeysChanged,
                )
            }
            Column(modifier = Modifier.align(Alignment.BottomCenter)) {
                SnackbarHost(hostState = snackbarHostState)
                if (isSelecting) {
                    // Remote protocols are read-only here, so Play is the only batch action.
                    // It needs at least one selected video; folders and other files are skipped.
                    val currentLayer = uiState.remoteLayers.lastOrNull()
                    val hasSelectedVideo = remember(currentLayer?.items, selectedKeys) {
                        currentLayer?.items?.any {
                            it.path in selectedKeys && !it.isDirectory && isVideoMedia(it.name, it.mimeType)
                        } == true
                    }
                    val playLabel = stringResource(R.string.selection_action_play)
                    AnimatedVisibility(
                        visible = !isListAtEnd,
                        enter = fadeIn() + scaleIn(),
                        exit = fadeOut() + scaleOut(),
                    ) {
                        SelectionActionBar(
                            actions = listOf(
                                SelectionAction(
                                    icon = Icons.Default.PlayArrow,
                                    label = playLabel,
                                    enabled = hasSelectedVideo,
                                    onClick = {
                                        val playlist = viewModel.collectSelectedVideoPlaylist()
                                        if (playlist.isNotEmpty()) {
                                            viewModel.onExitSelection()
                                            onPlayAllVideos(playlist)
                                        }
                                    },
                                ),
                            ),
                        )
                    }
                }
            }
        }
    }
}

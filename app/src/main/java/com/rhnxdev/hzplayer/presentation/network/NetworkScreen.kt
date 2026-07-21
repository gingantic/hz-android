package com.rhnxdev.hzplayer.presentation.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.util.isVideoOrStreamDefault
import com.rhnxdev.hzplayer.domain.model.SortDirection
import com.rhnxdev.hzplayer.domain.model.SortType
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.network.components.CredentialDialog
import com.rhnxdev.hzplayer.presentation.network.components.NetworkHomeContent
import com.rhnxdev.hzplayer.presentation.network.components.ServerBrowseStackContent
import com.rhnxdev.hzplayer.presentation.network.components.ServerConfigDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onPlayStream: (url: String, title: String, isVideo: Boolean, mimeType: String?) -> Unit = { _, _, _, _ -> },
    onPlayRemoteFile: (uri: String, title: String, isVideo: Boolean, mimeType: String?) -> Unit = { _, _, _, _ -> },
    onPlayAllVideos: (List<VideoItem>) -> Unit = {},
    onOpenBrowser: () -> Unit = {}, // kept as stub — callers still pass it
    fullScreenOverlay: Boolean = false,
    isActive: Boolean = true,
    modifier: Modifier = Modifier,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val scope = rememberCoroutineScope()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

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
            if (uiState.mode == NetworkScreenMode.SERVER_BROWSE) {
                if (!isSearchActive) {
                    // Media mode toggle
                    androidx.compose.material3.IconButton(onClick = viewModel::onToggleMediaMode) {
                        androidx.compose.material3.Icon(
                            imageVector = if (uiState.isMediaMode) Icons.AutoMirrored.Filled.ViewList
                            else Icons.Filled.PhotoLibrary,
                            contentDescription = if (uiState.isMediaMode) stringResource(R.string.list_view) else stringResource(R.string.media_view),
                        )
                    }
                }
                var showSortMenu by remember { mutableStateOf(false) }
                androidx.compose.material3.IconButton(onClick = { showSortMenu = true }) {
                    androidx.compose.material3.Icon(
                        imageVector = Icons.AutoMirrored.Filled.Sort,
                        contentDescription = stringResource(R.string.network_sort_cd),
                    )
                }
                androidx.compose.material3.DropdownMenu(
                    expanded = showSortMenu,
                    onDismissRequest = { showSortMenu = false },
                ) {
                    listOf(
                        SortType.TITLE to stringResource(R.string.sort_by_name),
                        SortType.DATE_MODIFIED to stringResource(R.string.sort_by_date),
                        SortType.FILE_SIZE to stringResource(R.string.sort_by_size),
                    ).forEach { (type, label) ->
                        androidx.compose.material3.DropdownMenuItem(
                            text = { androidx.compose.material3.Text(label) },
                            onClick = {
                                viewModel.onSortChanged(type, uiState.sortDirection)
                                showSortMenu = false
                            },
                            leadingIcon = if (uiState.sortType == type) {
                                {
                                    androidx.compose.material3.Icon(
                                        imageVector = Icons.Filled.Check,
                                        contentDescription = null,
                                    )
                                }
                            } else null,
                        )
                    }
                    HorizontalDivider()
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(stringResource(R.string.sort_ascending)) },
                        onClick = {
                            viewModel.onSortChanged(uiState.sortType, SortDirection.ASCENDING)
                            showSortMenu = false
                        },
                        leadingIcon = if (uiState.sortDirection == SortDirection.ASCENDING) {
                            { androidx.compose.material3.Icon(Icons.Filled.Check, null) }
                        } else null,
                    )
                    androidx.compose.material3.DropdownMenuItem(
                        text = { androidx.compose.material3.Text(stringResource(R.string.sort_descending)) },
                        onClick = {
                            viewModel.onSortChanged(uiState.sortType, SortDirection.DESCENDING)
                            showSortMenu = false
                        },
                        leadingIcon = if (uiState.sortDirection == SortDirection.DESCENDING) {
                            { androidx.compose.material3.Icon(Icons.Filled.Check, null) }
                        } else null,
                    )
                }
            }
        },
    ) {
        when (uiState.mode) {
            NetworkScreenMode.HOME -> NetworkHomeContent(
                uiState = uiState,
                onStreamUrlChanged = viewModel::onStreamUrlChanged,
                onPlayStream = {
                    val url = viewModel.onPlayStream() ?: return@NetworkHomeContent
                    val title = url.substringAfterLast("/").ifEmpty { url }
                    scope.launch {
                        val res = viewModel.resolveStreamMedia(url)
                        onPlayStream(url, title, res.isVideo, res.mimeType)
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
                    onPlayStream(url, item.title, isVideoOrStreamDefault(url), null)
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
                fullScreenOverlay = fullScreenOverlay,
                onPlayAllVideos = {
                    val playlist = viewModel.collectVideoPlaylist()
                    if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                },
            )
        }
    }
}

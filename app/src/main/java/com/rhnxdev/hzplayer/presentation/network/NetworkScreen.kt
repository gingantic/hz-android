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
import com.rhnxdev.hzplayer.core.components.ViewSortBottomSheet
import com.rhnxdev.hzplayer.core.util.isVideoOrStreamDefault
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
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
                    val headersMap = item.headersMap.toMutableMap()
                    val pageUrl = item.pageUrl.orEmpty()
                    val liveCookies = runCatching {
                        if (pageUrl.isNotBlank()) android.webkit.CookieManager.getInstance().getCookie(pageUrl) else null
                    }.getOrNull()
                    if (!liveCookies.isNullOrBlank() && headersMap.keys.none { it.equals("Cookie", ignoreCase = true) }) {
                        headersMap["Cookie"] = liveCookies
                    }
                    if (pageUrl.isNotBlank() && headersMap.keys.none { it.equals("Referer", ignoreCase = true) }) {
                        headersMap["Referer"] = pageUrl
                    }
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
                fullScreenOverlay = fullScreenOverlay,
                onPlayAllVideos = {
                    val playlist = viewModel.collectVideoPlaylist()
                    if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                },
                onPlayAsAudio = { item ->
                    val uri = viewModel.buildPlaybackUri(item.path) ?: item.path
                    onPlayRemoteFile(uri, item.name, false, item.mimeType)
                },
            )
        }
    }
}

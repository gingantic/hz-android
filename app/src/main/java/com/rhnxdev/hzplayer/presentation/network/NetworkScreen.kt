package com.rhnxdev.hzplayer.presentation.network

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.presentation.network.components.RemoteFileListItem
import com.rhnxdev.hzplayer.presentation.network.components.ServerCard
import com.rhnxdev.hzplayer.presentation.network.components.ServerConfigDialog
import com.rhnxdev.hzplayer.presentation.network.components.StreamHistoryListItem

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NetworkScreen(
    onPlayStream: (url: String, title: String, isVideo: Boolean) -> Unit = { _, _, _ -> },
    onPlayRemoteFile: (uri: String, title: String, isVideo: Boolean) -> Unit = { _, _, _ -> },
    fullScreenOverlay: Boolean = false,
    modifier: Modifier = Modifier,
    viewModel: NetworkViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Intercept back button when browsing a remote server
    BackHandler(
        enabled = uiState.mode == NetworkScreenMode.SERVER_BROWSE && !fullScreenOverlay,
    ) {
        viewModel.onRemoteNavigateUp()
    }

    if (uiState.showServerDialog) {
        ServerConfigDialog(
            initialServer = uiState.editingServer,
            onSave = viewModel::onSaveServer,
            onDismiss = viewModel::onDismissServerDialog,
        )
    }

    Column(modifier = modifier.fillMaxSize()) {
        HzPlayerTopBar(
            title = when (uiState.mode) {
                NetworkScreenMode.HOME -> "Network"
                NetworkScreenMode.SERVER_BROWSE -> uiState.browsingServer?.name ?: "Server"
            },
            showBack = uiState.mode == NetworkScreenMode.SERVER_BROWSE,
            onBack = { viewModel.onRemoteNavigateUp() },
        )

        when (uiState.mode) {
            NetworkScreenMode.HOME -> NetworkHomeContent(
                uiState = uiState,
                onStreamUrlChanged = viewModel::onStreamUrlChanged,
                onPlayStream = {
                    val url = viewModel.onPlayStream() ?: return@NetworkHomeContent
                    val title = url.substringAfterLast("/").ifEmpty { url }
                    onPlayStream(url, title, isVideoUrl(url))
                },
                onAddServer = viewModel::onAddServerClicked,
                onBrowseServer = viewModel::onBrowseServer,
                onEditServer = viewModel::onEditServer,
                onDeleteServer = viewModel::onDeleteServer,
                onPlayHistoryItem = { item ->
                    val url = viewModel.onPlayHistoryItem(item)
                    onPlayStream(url, item.title, isVideoUrl(url))
                },
                onToggleFavorite = viewModel::onToggleFavorite,
                onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
                onClearHistory = viewModel::onClearHistory,
            )

            NetworkScreenMode.SERVER_BROWSE -> ServerBrowseContent(
                uiState = uiState,
                onFolderClicked = viewModel::onRemoteFolderClicked,
                onFileClicked = { item ->
                    val uri = viewModel.buildPlaybackUri(item.path) ?: return@ServerBrowseContent
                    onPlayRemoteFile(uri, item.name, isVideoUrl(item.name))
                },
                onBreadcrumbClicked = viewModel::onRemoteBreadcrumbClicked,
                onRetry = viewModel::onRetryBrowse,
                onRefresh = viewModel::onRefreshBrowse,
                onBackToHome = viewModel::onBackToHome,
            )
        }
    }
}

// ── Home content (stream URL + servers + history) ──────────────────

@Composable
private fun NetworkHomeContent(
    uiState: NetworkUiState,
    onStreamUrlChanged: (String) -> Unit,
    onPlayStream: () -> Unit,
    onAddServer: () -> Unit,
    onBrowseServer: (ServerConfig) -> Unit,
    onEditServer: (ServerConfig) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onPlayHistoryItem: (StreamHistoryItem) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.lg),
    ) {
        // Open Stream section
        item {
            Column(modifier = Modifier.padding(horizontal = Spacing.lg)) {
                Text(
                    text = "Open Network Stream",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = uiState.streamUrl,
                    onValueChange = onStreamUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("http://, https://, rtsp://...") },
                    isError = uiState.streamUrlError != null,
                    supportingText = uiState.streamUrlError?.let { error ->
                        { Text(error) }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(Spacing.md),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                    keyboardActions = KeyboardActions(
                        onDone = {
                            if (uiState.streamUrl.isNotBlank()) {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onPlayStream()
                            }
                        }
                    ),
                    trailingIcon = {
                        FilledTonalButton(
                            onClick = {
                                keyboardController?.hide()
                                focusManager.clearFocus()
                                onPlayStream()
                            },
                            modifier = Modifier.padding(end = Spacing.xs),
                            enabled = uiState.streamUrl.isNotBlank(),
                        ) {
                            Icon(
                                imageVector = Icons.Filled.PlayArrow,
                                contentDescription = "Play",
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text("Play")
                        }
                    },
                )
            }
        }

        // Saved Servers section
        item {
            Column {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Saved Servers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onAddServer) {
                        Icon(
                            imageVector = HzPlayerIcons.Add,
                            contentDescription = "Add server",
                        )
                    }
                }

                if (uiState.savedServers.isEmpty()) {
                    Text(
                        text = "No servers added yet",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                } else {
                    LazyRow(
                        contentPadding = PaddingValues(horizontal = Spacing.lg),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                    ) {
                        items(
                            items = uiState.savedServers,
                            key = { it.id },
                        ) { server ->
                            ServerCard(
                                server = server,
                                onClick = { onBrowseServer(server) },
                                onEdit = { onEditServer(server) },
                                onDelete = { onDeleteServer(server.id) },
                            )
                        }
                    }
                }
            }
        }

        // Stream History section
        item {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.lg),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Stream History",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.streamHistory.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) {
                        Text("Clear")
                    }
                }
            }
        }

        if (uiState.streamHistory.isEmpty()) {
            item {
                Text(
                    text = "No streams played yet",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        } else {
            items(
                items = uiState.streamHistory,
                key = { it.id },
            ) { historyItem ->
                StreamHistoryListItem(
                    item = historyItem,
                    onClick = { onPlayHistoryItem(historyItem) },
                    onToggleFavorite = { onToggleFavorite(historyItem.id) },
                    onDelete = { onDeleteHistoryItem(historyItem.id) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        item { Spacer(modifier = Modifier.height(Spacing.xxl)) }
    }
}

// ── Server browse content (mirrors FileBrowserScreen) ──────────────

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ServerBrowseContent(
    uiState: NetworkUiState,
    onFolderClicked: (RemoteFileItem) -> Unit,
    onFileClicked: (RemoteFileItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    onBackToHome: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier.fillMaxSize()) {
        // Breadcrumb bar — identical to FileBrowserScreen
        NetworkBreadcrumbBar(
            breadcrumbs = uiState.remoteBreadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        // Content with pull-to-refresh — identical pattern to FileBrowserScreen
        Box(modifier = Modifier.weight(1f).fillMaxSize()) {
            PullToRefreshBox(
                isRefreshing = uiState.remoteBrowseLoading && uiState.remoteItems.isNotEmpty(),
                onRefresh = onRefresh,
                modifier = Modifier.fillMaxSize(),
            ) {
                when {
                    uiState.remoteBrowseLoading && uiState.remoteItems.isEmpty() -> {
                        // Shimmer loading state
                        com.rhnxdev.hzplayer.core.components.MediaLoadingState(
                            itemCount = 6,
                            shape = com.rhnxdev.hzplayer.core.components.ShimmerShape.LIST_ITEM,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }

                    uiState.remoteBrowseError != null -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center,
                        ) {
                            MediaErrorState(
                                title = "Could not load files",
                                subtitle = uiState.remoteBrowseError,
                                onRetry = onRetry,
                            )
                        }
                    }

                    !uiState.remoteBrowseLoading && uiState.remoteItems.isEmpty() -> {
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .verticalScroll(rememberScrollState()),
                            contentAlignment = Alignment.Center,
                        ) {
                            MediaEmptyState(
                                icon = Icons.Filled.Folder,
                                title = "This folder is empty",
                                subtitle = "Nothing to show here.",
                                modifier = Modifier.fillMaxSize(),
                            )
                        }
                    }

                    else -> {
                        LazyColumn(
                            modifier = Modifier.fillMaxSize(),
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            verticalArrangement = Arrangement.spacedBy(Spacing.xs),
                        ) {
                            items(
                                items = uiState.remoteItems,
                                key = { it.path },
                            ) { item ->
                                RemoteFileListItem(
                                    item = item,
                                    onClick = {
                                        if (item.isDirectory) onFolderClicked(item)
                                        else onFileClicked(item)
                                    },
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

// ── Breadcrumb bar — identical to FileBrowserScreen ────────────────

@Composable
private fun NetworkBreadcrumbBar(
    breadcrumbs: List<RemoteBreadcrumb>,
    onBreadcrumbClicked: (String) -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        breadcrumbs.forEachIndexed { index, crumb ->
            val isLast = index == breadcrumbs.lastIndex

            Text(
                text = crumb.name,
                style = MaterialTheme.typography.labelLarge,
                color = if (isLast) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = if (!isLast) Modifier.clickable { onBreadcrumbClicked(crumb.path) }
                else Modifier,
            )

            if (!isLast) {
                Icon(
                    imageVector = Icons.Default.ChevronRight,
                    contentDescription = null,
                    modifier = Modifier
                        .padding(horizontal = 4.dp)
                        .width(16.dp)
                        .height(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                )
            }
        }
    }
}

// ── Utility ────────────────────────────────────────────────────────

private val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
    "m4v", "mpg", "mpeg", "ts", "mts", "vob", "m3u8", "mpd",
)

private fun isVideoUrl(urlOrName: String): Boolean {
    val ext = urlOrName.substringAfterLast('.', "").substringBefore('?').lowercase()
    return ext in VIDEO_EXTENSIONS
}

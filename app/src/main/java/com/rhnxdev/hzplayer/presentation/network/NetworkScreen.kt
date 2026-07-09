package com.rhnxdev.hzplayer.presentation.network

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import com.rhnxdev.hzplayer.core.util.ServerDiscoverer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ViewList
import androidx.compose.material.icons.filled.ViewModule
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PhotoLibrary
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.components.BreadcrumbBar
import com.rhnxdev.hzplayer.core.components.DirectoryBrowsePane
import com.rhnxdev.hzplayer.core.components.FileItemData
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.core.util.isDocumentExtension
import com.rhnxdev.hzplayer.core.util.isBinaryExtension
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.domain.model.SortType
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
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

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

    uiState.discoveredServerCredential?.let { request ->
        CredentialDialog(
            server = request.server,
            onProvided = request.onProvided,
            onDismiss = viewModel::onDismissCredentialDialog,
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
            marqueeTitle = uiState.mode == NetworkScreenMode.SERVER_BROWSE,
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
                                contentDescription = if (uiState.isMediaMode) "List view" else "Media view",
                            )
                        }
                        // Search toggle
                        androidx.compose.material3.IconButton(onClick = viewModel::onSearchToggle) {
                            androidx.compose.material3.Icon(
                                imageVector = Icons.Filled.Search,
                                contentDescription = "Search",
                            )
                        }
                    }
                    var showSortMenu by remember { mutableStateOf(false) }
                    androidx.compose.material3.IconButton(onClick = { showSortMenu = true }) {
                        androidx.compose.material3.Icon(
                            imageVector = Icons.AutoMirrored.Filled.Sort,
                            contentDescription = "Sort",
                        )
                    }
                    androidx.compose.material3.DropdownMenu(
                        expanded = showSortMenu,
                        onDismissRequest = { showSortMenu = false },
                    ) {
                        listOf(
                            SortType.TITLE to "Sort by Name",
                            SortType.DATE_MODIFIED to "Sort by Date",
                            SortType.FILE_SIZE to "Sort by Size",
                        ).forEach { (type, label) ->
                            androidx.compose.material3.DropdownMenuItem(
                                text = { androidx.compose.material3.Text(label) },
                                onClick = {
                                    viewModel.onSortChanged(type)
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
                    }
                }
            },
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
                    onPlayStream(url, item.title, isVideoUrl(url))
                },
                onToggleFavorite = viewModel::onToggleFavorite,
                onDeleteHistoryItem = viewModel::onDeleteHistoryItem,
                onClearHistory = viewModel::onClearHistory,
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
                    onPlayRemoteFile(uri, item.name, isVideoUrl(item.name))
                },
                onBreadcrumbClicked = viewModel::onRemoteBreadcrumbClicked,
                onRetry = viewModel::onRetryBrowse,
                onRefresh = viewModel::onRefreshBrowse,
                buildPlaybackUri = { path -> viewModel.buildPlaybackUri(path) },
            )
        }
    }
}

// ── Home content (stream URL + servers + history) ──────────────────

@Composable
private fun NetworkHomeContent(
    uiState: NetworkUiState,
    onScanNetwork: () -> Unit,
    onStopScan: () -> Unit,
    onStreamUrlChanged: (String) -> Unit,
    onPlayStream: () -> Unit,
    onAddServer: () -> Unit,
    onBrowseServer: (ServerConfig) -> Unit,
    onEditServer: (ServerConfig) -> Unit,
    onDeleteServer: (Long) -> Unit,
    onDiscoveredServerTapped: (ServerConfig) -> Unit,
    onSaveDiscoveredServer: (ServerConfig) -> Unit,
    onDismissDiscoveredServer: (ServerConfig) -> Unit,
    onPlayHistoryItem: (StreamHistoryItem) -> Unit,
    onToggleFavorite: (Long) -> Unit,
    onDeleteHistoryItem: (Long) -> Unit,
    onClearHistory: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboardController = LocalSoftwareKeyboardController.current
    val focusManager = LocalFocusManager.current

    val context = LocalContext.current
    val nearbyWifiPermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { granted -> if (granted) onScanNetwork() }
    val onScanClick: () -> Unit = {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, ServerDiscoverer.NEARBY_WIFI_PERMISSION) != PackageManager.PERMISSION_GRANTED
        ) {
            nearbyWifiPermissionLauncher.launch(ServerDiscoverer.NEARBY_WIFI_PERMISSION)
        } else {
            onScanNetwork()
        }
    }

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(vertical = Spacing.md),
        verticalArrangement = Arrangement.spacedBy(Spacing.xl),
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
                } else if (uiState.isHomeListView) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        uiState.savedServers.forEach { server ->
                            key(server.id) {
                                ServerCard(
                                    server = server,
                                    onClick = { onBrowseServer(server) },
                                    onEdit = { onEditServer(server) },
                                    onDelete = { onDeleteServer(server.id) },
                                    dense = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
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
                                modifier = Modifier.width(180.dp),
                            )
                        }
                    }
                }
            }
        }

        // Discovery Servers section (only when on compatible network)
        if (uiState.isOnCompatibleNetwork) {
            item {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = "Discovery Servers",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (uiState.isDiscovering) onStopScan() else onScanClick() }) {
                            val transition = rememberInfiniteTransition(label = "scan")
                            val alpha by transition.animateFloat(
                                initialValue = 0.3f, targetValue = 1f,
                                animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                                label = "alpha",
                            )
                            Icon(
                                imageVector = HzPlayerIcons.Network,
                                contentDescription = if (uiState.isDiscovering) "Stop scan" else "Scan network",
                                modifier = Modifier.size(24.dp).then(if (uiState.isDiscovering) Modifier.alpha(alpha) else Modifier),
                            )
                        }
                    }
                }

                if (!uiState.isDiscovering && uiState.discoveredServers.isEmpty()) {
                    Text(
                        text = "No servers discovered",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                    )
                } else if (uiState.discoveredServers.isNotEmpty()) {
                    if (uiState.isHomeListView) {
                    Column(
                        modifier = Modifier.padding(horizontal = Spacing.lg),
                        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        uiState.discoveredServers.forEach { server ->
                            key(server.host) {
                                ServerCard(
                                    server = server,
                                    onClick = { onDiscoveredServerTapped(server) },
                                    showMenu = false,
                                    dense = true,
                                    modifier = Modifier.fillMaxWidth(),
                                )
                            }
                        }
                    }
                    } else {
                        LazyRow(
                            contentPadding = PaddingValues(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.md),
                        ) {
                            items(
                                items = uiState.discoveredServers,
                                key = { it.host },
                            ) { server ->
                                ServerCard(
                                    server = server,
                                    onClick = { onDiscoveredServerTapped(server) },
                                    showMenu = false,
                                    modifier = Modifier.width(180.dp),
                                )
                            }
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

// ── Server browse — layer stack ────────────────────────────────────

/**
 * Renders remote directory layers as a stack in a [Box].
 * Each layer keeps its own [LazyListState] naturally — no save/restore needed.
 */
@Composable
private fun ServerBrowseStackContent(
    uiState: NetworkUiState,
    searchQuery: String,
    isSearchActive: Boolean,
    onSearchToggle: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onFolderClicked: (RemoteFileItem) -> Unit,
    onFileClicked: (RemoteFileItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    buildPlaybackUri: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val topIndex = uiState.remoteLayers.lastIndex
        val noopAction: () -> Unit = {}
        val noopFolder: (RemoteFileItem) -> Unit = {}
        val noopBreadcrumb: (String) -> Unit = {}

        val topLayer = uiState.remoteLayers.getOrNull(topIndex)
        uiState.remoteLayers.forEachIndexed { index, layer ->
            val isTop = index == topIndex
            key(layer.path) {
                RemoteDirectoryLayerView(
                    layer = layer,
                    searchQuery = if (isTop) searchQuery else "",
                    isSearchActive = if (isTop) isSearchActive else false,
                    mediaMode = uiState.isMediaMode,
                    onFolderClicked = if (isTop) onFolderClicked else noopFolder,
                    onFileClicked = if (isTop) onFileClicked else noopFolder,
                    onBreadcrumbClicked = if (isTop) onBreadcrumbClicked else noopBreadcrumb,
                    onRetry = if (isTop) onRetry else noopAction,
                    onRefresh = if (isTop) onRefresh else noopAction,
                    buildPlaybackUri = buildPlaybackUri,
                    modifier = (if (isTop) Modifier else Modifier.alpha(0f)).fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun RemoteDirectoryLayerView(
    layer: RemoteDirectoryLayer,
    searchQuery: String,
    isSearchActive: Boolean,
    mediaMode: Boolean,
    onFolderClicked: (RemoteFileItem) -> Unit,
    onFileClicked: (RemoteFileItem) -> Unit,
    onBreadcrumbClicked: (String) -> Unit,
    onRetry: () -> Unit,
    onRefresh: () -> Unit,
    buildPlaybackUri: (String) -> String?,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()

    val visibleItems = remember(layer.items, mediaMode) {
        when {
            mediaMode -> layer.items.filter { it.isDirectory || isVideoExtension(it.name) }
            else -> layer.items.filter { it.isDirectory || (!isDocumentExtension(it.name) && !isBinaryExtension(it.name)) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(
            breadcrumbs = layer.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        DirectoryBrowsePane(
            items = visibleItems.map { it.toFileItemData(buildPlaybackUri(it.path)) },
            isLoading = layer.isLoading,
            isEmpty = if (mediaMode) visibleItems.isEmpty() else layer.isEmpty,
            error = layer.error,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
            mediaMode = mediaMode,
            onItemClick = { data ->
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                if (data.isDirectory) onFolderClicked(item)
                else onFileClicked(item)
            },
            onRefresh = onRefresh,
            onRetry = onRetry,
            listState = listState,
            modifier = Modifier.weight(1f).fillMaxSize(),
        )
    }
}

private fun RemoteFileItem.toFileItemData(playbackUri: String? = null) = FileItemData(
    id = path,
    name = name,
    path = path,
    isDirectory = isDirectory,
    fileSize = fileSize,
    childCount = childCount,
    dateModified = dateModified,
    mimeType = mimeType,
    playbackUri = playbackUri,
)

// ── Credential dialog for discovered servers ──────────────────────

@Composable
private fun CredentialDialog(
    server: ServerConfig,
    onProvided: (username: String, password: String, saveToSaved: Boolean) -> Unit,
    onDismiss: () -> Unit,
) {
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var saveToSaved by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = "${server.protocol.name} • ${server.host}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth(),
                )
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(
                        checked = saveToSaved,
                        onCheckedChange = { saveToSaved = it },
                    )
                    Text(
                        text = "Save to Saved Servers",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onProvided(username, password, saveToSaved) }) {
                Text("Connect")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

// ── Utility ────────────────────────────────────────────────────────

private fun isVideoUrl(urlOrName: String): Boolean = isVideoExtension(urlOrName)

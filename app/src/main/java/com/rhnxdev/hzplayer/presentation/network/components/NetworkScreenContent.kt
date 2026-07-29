package com.rhnxdev.hzplayer.presentation.network.components

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.content.ContextCompat
import android.os.Build
import android.content.pm.PackageManager
import com.rhnxdev.hzplayer.core.util.ServerDiscoverer
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Language
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.BreadcrumbBar
import com.rhnxdev.hzplayer.core.components.DirectoryBrowsePane
import com.rhnxdev.hzplayer.core.components.FileItemData
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.core.util.isDocumentExtension
import com.rhnxdev.hzplayer.core.util.isBinaryExtension
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.model.StreamHistoryItem
import com.rhnxdev.hzplayer.presentation.network.NetworkUiState
import com.rhnxdev.hzplayer.presentation.network.RemoteDirectoryLayer
import com.rhnxdev.hzplayer.presentation.network.components.ServerCard
import com.rhnxdev.hzplayer.presentation.network.components.StreamHistoryListItem

// ── Home content (stream URL + servers + history) ──────────────────

@Composable
internal fun NetworkHomeContent(
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
    onOpenBrowser: (String?) -> Unit = {},
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
                    text = stringResource(R.string.open_network_stream),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                Spacer(modifier = Modifier.height(Spacing.sm))
                OutlinedTextField(
                    value = uiState.streamUrl,
                    onValueChange = onStreamUrlChanged,
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text(stringResource(R.string.stream_url_placeholder)) },
                    isError = uiState.isStreamUrlError,
                    supportingText = if (uiState.isStreamUrlError) {
                        { Text(stringResource(R.string.stream_url_error)) }
                    } else null,
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
                                contentDescription = stringResource(R.string.play),
                                modifier = Modifier.size(20.dp),
                            )
                            Spacer(modifier = Modifier.width(Spacing.xs))
                            Text(stringResource(R.string.play))
                        }
                    },
                )

                // Browser launch button
                Spacer(modifier = Modifier.height(Spacing.sm))
                FilledTonalButton(
                    onClick = { onOpenBrowser(uiState.streamUrl.ifBlank { null }) },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Icon(
                        imageVector = Icons.Filled.Language,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                    )
                    Spacer(modifier = Modifier.width(Spacing.xs))
                    Text(stringResource(id = com.rhnxdev.hzplayer.R.string.open_browser))
                }
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
                        text = stringResource(R.string.saved_servers),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    IconButton(onClick = onAddServer) {
                        Icon(
                            imageVector = HzPlayerIcons.Add,
                            contentDescription = stringResource(R.string.add_server_cd),
                        )
                    }
                }

                if (uiState.savedServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_servers_title),
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
                        text = stringResource(R.string.discovery_servers),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        IconButton(onClick = { if (uiState.isDiscovering) onStopScan() else onScanClick() }) {
                            // ponytail: only run the infinite transition while actually
                            // scanning — otherwise it recomposes this subtree every 800ms forever.
                            if (uiState.isDiscovering) {
                                val transition = rememberInfiniteTransition(label = "scan")
                                val alpha by transition.animateFloat(
                                    initialValue = 0.3f, targetValue = 1f,
                                    animationSpec = infiniteRepeatable(tween(800), RepeatMode.Reverse),
                                    label = "alpha",
                                )
                                Icon(
                                    imageVector = HzPlayerIcons.Network,
                                    contentDescription = stringResource(R.string.stop_scan_cd),
                                    modifier = Modifier.size(24.dp).alpha(alpha),
                                )
                            } else {
                                Icon(
                                    imageVector = HzPlayerIcons.Network,
                                    contentDescription = stringResource(R.string.scan_network_cd),
                                    modifier = Modifier.size(24.dp),
                                )
                            }
                        }
                    }
                }

                if (!uiState.isDiscovering && uiState.discoveredServers.isEmpty()) {
                    Text(
                        text = stringResource(R.string.no_servers_discovered),
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
                    text = stringResource(R.string.stream_history),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                )
                if (uiState.streamHistory.isNotEmpty()) {
                    TextButton(onClick = onClearHistory) {
                        Text(stringResource(R.string.clear))
                    }
                }
            }
        }

        if (uiState.streamHistory.isEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.no_streams_title),
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
internal fun ServerBrowseStackContent(
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
    getScrollState: (String) -> Pair<Int, Int>,
    getScrollStateIsAtEnd: (String) -> Boolean,
    saveScrollState: (String, Int, Int, Boolean) -> Unit,
    fullScreenOverlay: Boolean = false,
    onPlayAllVideos: () -> Unit = {},
    onPlayAsAudio: (RemoteFileItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(modifier = modifier.fillMaxSize()) {
        val topIndex = uiState.remoteLayers.lastIndex
        val noopAction: () -> Unit = {}
        val noopFolder: (RemoteFileItem) -> Unit = {}
        val noopBreadcrumb: (String) -> Unit = {}
        val noopAtEnd: (Boolean) -> Unit = {}
        var isListAtEnd by remember { mutableStateOf(false) }
        val topLayerAtEnd: (Boolean) -> Unit = { isListAtEnd = it }

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
                    getScrollState = getScrollState,
                    getScrollStateIsAtEnd = getScrollStateIsAtEnd,
                    saveScrollState = saveScrollState,
                    fullScreenOverlay = fullScreenOverlay,
                    isTopLayer = isTop,
                    onPlayAsAudio = if (isTop) onPlayAsAudio else noopFolder,
                    onListAtEndChanged = if (isTop) topLayerAtEnd else noopAtEnd,
                    modifier = (if (isTop) Modifier else Modifier.alpha(0f)).fillMaxSize(),
                )
            }
        }

        // Play All FAB
        val hasVideos = uiState.remoteLayers.lastOrNull()?.items?.any {
            !it.isDirectory && (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name))
        } == true
        if (hasVideos && !isSearchActive) {
            // Hide the FAB at the bottom of the list so it doesn't cover the last item
            AnimatedVisibility(
                visible = !isListAtEnd,
                enter = fadeIn() + scaleIn(),
                exit = fadeOut() + scaleOut(),
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(16.dp),
            ) {
                FloatingActionButton(
                    onClick = onPlayAllVideos,
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    elevation = FloatingActionButtonDefaults.elevation(
                        defaultElevation = 6.dp,
                        pressedElevation = 12.dp
                    )
                ) {
                    Icon(
                        imageVector = Icons.Default.PlayArrow,
                        contentDescription = stringResource(R.string.play_all_cd),
                    )
                }
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
    getScrollState: (String) -> Pair<Int, Int>,
    getScrollStateIsAtEnd: (String) -> Boolean,
    saveScrollState: (String, Int, Int, Boolean) -> Unit,
    fullScreenOverlay: Boolean = false,
    isTopLayer: Boolean = false,
    onPlayAsAudio: (RemoteFileItem) -> Unit = {},
    onListAtEndChanged: (Boolean) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    // Only store the item *index* — pixel offsets are orientation-dependent and cause
    // jumps when the device rotates or the layout changes. Index-only restore always
    // lands cleanly at the right item with zero extra offset.
    val initialIndex = remember<Int>(layer.path) { getScrollState(layer.path).first }
    val listState = remember<LazyListState>(layer.path) {
        LazyListState(
            firstVisibleItemIndex = initialIndex,
            firstVisibleItemScrollOffset = 0,
        )
    }

    // On rotation the LazyListState is reused (keyed only on layer.path), so the
    // constructor values don't apply again. LazyColumn re-lays out with the new
    // viewport height and may auto-scroll backward when the saved index is near the
    // end — it pulls back to fill the taller/shorter screen (end-clamping).
    // Fix: if "isAtEnd" was saved, jump to the absolute last item so LazyColumn
    // applies its natural end-anchor. Otherwise restore the exact saved index.
    val currentOrientation = LocalConfiguration.current.orientation
    androidx.compose.runtime.LaunchedEffect(currentOrientation, layer.path) {
        val total = listState.layoutInfo.totalItemsCount
        if (getScrollStateIsAtEnd(layer.path) && total > 0) {
            listState.scrollToItem(total - 1, 0)
        } else {
            val savedIndex = getScrollState(layer.path).first
            listState.scrollToItem(savedIndex, 0)
        }
    }

    androidx.compose.runtime.LaunchedEffect(listState, layer.path) {
        var wasScrolling = false
        androidx.compose.runtime.snapshotFlow {
            listState.isScrollInProgress to listState.firstVisibleItemIndex
        }
        .collect { (isScrolling, index) ->
            if (isScrolling || wasScrolling) {
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isAtEnd = info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
                saveScrollState(layer.path, index, 0, isAtEnd)
            }
            wasScrolling = isScrolling
        }
    }

    // Report when the list is scrolled to the very end so the Play All FAB
    // can hide instead of covering the last item. Lists too short to scroll
    // never report true, keeping the FAB available.
    androidx.compose.runtime.LaunchedEffect(listState, layer.path) {
        androidx.compose.runtime.snapshotFlow {
            listState.canScrollBackward && !listState.canScrollForward
        }.collect { onListAtEndChanged(it) }
    }

    // When the full-screen overlay (video/audio player) closes, the underlying
    // list may have been re-anchored by the viewport resize while it was hidden.
    // Snap the top layer back using the same end-aware logic as the rotation effect.
    androidx.compose.runtime.LaunchedEffect(fullScreenOverlay) {
        if (!fullScreenOverlay && isTopLayer) {
            val total = listState.layoutInfo.totalItemsCount
            if (getScrollStateIsAtEnd(layer.path) && total > 0) {
                listState.scrollToItem(total - 1, 0)
            } else {
                val savedIndex = getScrollState(layer.path).first
                listState.scrollToItem(savedIndex, 0)
            }
        }
    }

    val visibleItems = remember(layer.items, mediaMode) {
        when {
            mediaMode -> layer.items.filter { it.isDirectory || (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name)) }
            else -> layer.items.filter { it.isDirectory || (!isDocumentExtension(it.name) && !isBinaryExtension(it.name)) }
        }
    }

    Column(modifier = modifier.fillMaxSize()) {
        BreadcrumbBar(
            breadcrumbs = layer.breadcrumbs,
            onBreadcrumbClicked = onBreadcrumbClicked,
        )

        // Show whenever items exist. They persist across refresh (not cleared while
        // isLoading), so the count stays put instead of vanishing and reappearing.
        if (visibleItems.isNotEmpty()) {
            val folders = visibleItems.count { it.isDirectory }
            val others = visibleItems.count { !it.isDirectory }
            Text(
                text = if (mediaMode) {
                    stringResource(R.string.dir_summary_media, folders, others)
                } else {
                    stringResource(R.string.dir_summary, folders, others)
                },
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = Spacing.md, top = Spacing.xs, bottom = Spacing.xs),
            )
        }

        DirectoryBrowsePane(
            items = visibleItems.map { it.toFileItemData(buildPlaybackUri(it.path)) },
            isLoading = layer.isLoading,
            isEmpty = if (mediaMode) visibleItems.isEmpty() else layer.isEmpty,
            error = layer.error,
            searchQuery = searchQuery,
            isSearchActive = isSearchActive,
            mediaMode = mediaMode,
            onItemClick = { data ->
                // Snapshot scroll position NOW — before navigation fires isFullScreen=true,
                // which hides the nav bar/rail, resizes the viewport, and causes the
                // LazyColumn to scroll backward to fill the new empty space at the bottom/side.
                val info = listState.layoutInfo
                val lastVisible = info.visibleItemsInfo.lastOrNull()?.index ?: 0
                val isAtEnd = info.totalItemsCount > 0 && lastVisible >= info.totalItemsCount - 1
                saveScrollState(layer.path, listState.firstVisibleItemIndex, 0, isAtEnd)
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                if (data.isDirectory) onFolderClicked(item)
                else onFileClicked(item)
            },
            onRefresh = onRefresh,
            onRetry = onRetry,
            onPlayAsAudio = { data ->
                val item = layer.items.find { it.path == data.path } ?: return@DirectoryBrowsePane
                onPlayAsAudio(item)
            },
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
    subfolderCount = subfolderCount,
    fileCount = fileCount,
    mediaCount = mediaCount,
    dateModified = dateModified,
    mimeType = mimeType,
    playbackUri = playbackUri,
)

// ── Credential dialog for discovered servers ──────────────────────

@Composable
internal fun CredentialDialog(
    server: ServerConfig,
    onProvided: (username: String, password: String, saveToSaved: Boolean) -> Unit,
    onDismiss: () -> Unit,
    error: String? = null,
) {
    var username by remember { mutableStateOf(server.username) }
    var password by remember { mutableStateOf("") }
    var saveToSaved by remember { mutableStateOf(true) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(server.name) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(Spacing.sm)) {
                Text(
                    text = stringResource(R.string.server_summary, server.protocol.name, server.host),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (error != null) {
                    Text(
                        text = error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text(stringResource(R.string.label_username)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text(stringResource(R.string.label_password)) },
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
                        text = stringResource(R.string.save_to_saved),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onProvided(username, password, saveToSaved) }) {
                Text(stringResource(R.string.connect))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        },
    )
}

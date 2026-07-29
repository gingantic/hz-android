package com.rhnxdev.hzplayer.presentation.browse

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.FloatingActionButtonDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerSearchableScaffold
import com.rhnxdev.hzplayer.core.util.ArchiveBrowsePath
import com.rhnxdev.hzplayer.core.util.isArchiveExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.model.FolderItem
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.presentation.browse.components.ArchivePasswordDialog
import com.rhnxdev.hzplayer.presentation.browse.components.DirectoryStackContent
import com.rhnxdev.hzplayer.presentation.browse.components.FileBrowserTopBarActions
import com.rhnxdev.hzplayer.presentation.browse.components.SolidArchiveWarningDialog
import com.rhnxdev.hzplayer.presentation.browse.components.StorageRootsContent
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun FileBrowserScreen(
    viewModel: FileBrowserViewModel = hiltViewModel(),
    onFileClicked: (FolderItem) -> Unit = {},
    onPlayAllVideos: (List<VideoItem>) -> Unit = {},
    onPlayAsAudio: (FolderItem) -> Unit = {},
    fullScreenOverlay: Boolean = false,
    isActive: Boolean = true,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val searchQuery by viewModel.search.searchQuery.collectAsStateWithLifecycle()
    val isSearchActive by viewModel.search.isSearchActive.collectAsStateWithLifecycle()

    val onNavigateUp: (() -> Unit)? =
        if (uiState.mode == FileBrowserMode.BROWSING) {
            { viewModel.onNavigateUp() }
        } else null

    val handleFileClicked: (FolderItem) -> Unit = { item ->
        if (isArchiveExtension(item.name) && ArchiveBrowsePath.isRealFilePath(item.path)) {
            viewModel.onOpenArchive(item)
        } else {
            onFileClicked(item)
        }
    }

    HzPlayerSearchableScaffold(
        title = if (uiState.mode == FileBrowserMode.ROOTS) "Browse" else "Files",
        isSearchActive = isSearchActive,
        searchQuery = searchQuery,
        onSearchToggle = viewModel::onSearchToggle,
        onSearchQueryChanged = viewModel::onSearchQueryChanged,
        onClearSearch = viewModel::onClearSearch,
        onNavigateUp = onNavigateUp,
        searchPlaceholder = "Search files...",
        fullScreenOverlay = fullScreenOverlay,
        isActive = isActive,
        actions = {
            if (uiState.mode == FileBrowserMode.BROWSING) {
                FileBrowserTopBarActions(
                    uiState = uiState,
                    isSearchActive = isSearchActive,
                    onToggleMediaMode = viewModel::onToggleMediaMode,
                    onSortChanged = viewModel::onSortChanged,
                )
            }
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (uiState.mode) {
                FileBrowserMode.ROOTS -> StorageRootsContent(
                    roots = uiState.roots,
                    favorites = uiState.favorites,
                    isLoading = uiState.isLoading,
                    onRootClicked = viewModel::onStorageRootClicked,
                    onFavoriteClicked = viewModel::onFavoriteClicked,
                    onToggleQuickAccess = viewModel::onToggleQuickAccess,
                    onRefresh = viewModel::onRefresh,
                )
                FileBrowserMode.BROWSING -> {
                    var isListAtEnd by remember { mutableStateOf(false) }

                    DirectoryStackContent(
                        layers = uiState.layers,
                        searchQuery = searchQuery,
                        isSearchActive = isSearchActive,
                        mediaMode = uiState.isMediaMode,
                        quickAccessPaths = uiState.quickAccessPaths,
                        onFolderClicked = viewModel::onFolderClicked,
                        onBreadcrumbClicked = viewModel::onBreadcrumbClicked,
                        onRetry = viewModel::onRetry,
                        onRefresh = viewModel::onRefresh,
                        getScrollState = viewModel::getScrollState,
                        saveScrollState = viewModel::saveScrollState,
                        fullScreenOverlay = fullScreenOverlay,
                        onFileClicked = handleFileClicked,
                        onPlayAsAudio = onPlayAsAudio,
                        onPlayAllFolder = { folder ->
                            viewModel.collectFolderVideoPlaylist(folder) { playlist ->
                                if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                            }
                        },
                        onToggleFavorite = { viewModel.onToggleQuickAccess(it.path) },
                        onListAtEndChanged = { isListAtEnd = it },
                    )

                    // Play All FAB (memoized video check)
                    val currentLayerItems = uiState.layers.lastOrNull()?.items
                    val hasVideos = remember(currentLayerItems) {
                        currentLayerItems?.any {
                            !it.isDirectory && (it.mimeType?.startsWith("video") == true || isVideoExtension(it.name))
                        } == true
                    }

                    if (hasVideos && !isSearchActive) {
                        // Hidden at the bottom of the list so it doesn't cover the last item
                        PlayAllFab(
                            visible = !isListAtEnd,
                            onClick = {
                                val playlist = viewModel.collectVideoPlaylist()
                                if (playlist.isNotEmpty()) onPlayAllVideos(playlist)
                            },
                            modifier = Modifier
                                .align(Alignment.BottomEnd)
                                .padding(16.dp),
                        )
                    }
                }
            }
        }
    }

    if (uiState.passwordPromptContainer != null) {
        ArchivePasswordDialog(
            archiveName = uiState.passwordPromptContainer!!.substringAfterLast('/'),
            onProvided = viewModel::onProvidePassword,
            onDismiss = viewModel::onCancelPasswordPrompt,
            error = uiState.passwordError
        )
    }
    if (uiState.solidArchiveWarningContainer != null) {
        SolidArchiveWarningDialog(
            archiveName = uiState.solidArchiveWarningContainer!!.name,
            onConfirm = { dontShowAgain -> viewModel.onConfirmSolidArchiveWarning(dontShowAgain) },
            onDismiss = viewModel::onDismissSolidArchiveWarning,
        )
    }
}

@Composable
private fun PlayAllFab(
    visible: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    AnimatedVisibility(
        visible = visible,
        enter = fadeIn() + scaleIn(),
        exit = fadeOut() + scaleOut(),
        modifier = modifier,
    ) {
        FloatingActionButton(
            onClick = onClick,
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

@PreviewLightDark
@Preview
@Composable
private fun FileBrowserScreenPreview() {
    HzPlayerTheme {
        FileBrowserScreen()
    }
}

package com.rhnxdev.hzplayer.presentation.player.components

import android.net.Uri
import java.io.File
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SheetValue
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.BreadcrumbBar
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaErrorState
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserMode
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserUiState
import com.rhnxdev.hzplayer.presentation.player.SubtitleBrowserViewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SubtitleFileBrowserBottomSheet(
    videoUri: String?,
    onDismiss: () -> Unit,
    onSubtitleSelected: (Uri, String) -> Unit,
    viewModel: SubtitleBrowserViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(videoUri) {
        viewModel.initVideoUri(videoUri)
    }

    // Intercept back button when not in ROOTS mode
    BackHandler(enabled = uiState.mode != SubtitleBrowserMode.ROOTS) {
        viewModel.onNavigateUp()
    }

    val sheetState = rememberModalBottomSheetState(
        skipPartiallyExpanded = true,
        confirmValueChange = { targetValue ->
            // Prevent swipe-to-dismiss drag gesture by rejecting the Hidden target state
            targetValue != SheetValue.Hidden
        }
    )

    val isBrowsing = uiState.mode != SubtitleBrowserMode.ROOTS
    val titleText = if (isBrowsing) {
        uiState.currentPath.substringAfterLast('/').ifEmpty { uiState.currentPath }
    } else {
        stringResource(R.string.browse_subtitles)
    }

    SheetScaffold(
        title = titleText,
        icon = if (isBrowsing) null else Icons.Default.Subtitles,
        onDismiss = onDismiss,
        sheetState = sheetState,
        navigationIcon = if (isBrowsing) {
            {
                IconButton(
                    onClick = { viewModel.onNavigateUp() },
                    modifier = Modifier.padding(end = 4.dp)
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = stringResource(R.string.navigate_up),
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        } else null,
        headerActions = {
            if (isBrowsing) {
                TextButton(onClick = { viewModel.loadLocalRoots() }) {
                    Icon(
                        imageVector = Icons.Default.Storage,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(16.dp),
                    )
                    Spacer(modifier = Modifier.width(4.dp))
                    Text(
                        text = stringResource(R.string.storages),
                        color = MaterialTheme.colorScheme.primary,
                        style = MaterialTheme.typography.labelLarge,
                    )
                }
            }
        },
        columnModifier = Modifier
            .fillMaxWidth()
            .fillMaxHeight(0.85f)
    ) {
        // Breadcrumb bar (only in browsing modes)
        val crumbs = when (uiState.mode) {
            SubtitleBrowserMode.BROWSING_LOCAL -> uiState.localBreadcrumbs
            SubtitleBrowserMode.BROWSING_REMOTE -> uiState.remoteBreadcrumbs
            else -> emptyList()
        }
        if (crumbs.isNotEmpty()) {
            BreadcrumbBar(
                breadcrumbs = crumbs,
                onBreadcrumbClicked = { path ->
                    when (uiState.mode) {
                        SubtitleBrowserMode.BROWSING_LOCAL -> viewModel.onLocalBreadcrumbClicked(path)
                        SubtitleBrowserMode.BROWSING_REMOTE -> viewModel.onRemoteBreadcrumbClicked(path)
                        else -> {}
                    }
                },
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        // Main Content Area
        Box(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
        ) {
            when {
                uiState.isLoading -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                uiState.error != null -> {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        contentAlignment = Alignment.Center
                    ) {
                        MediaErrorState(
                            title = stringResource(R.string.could_not_load_files),
                            subtitle = uiState.error ?: "",
                            onRetry = {
                                if (uiState.mode == SubtitleBrowserMode.BROWSING_LOCAL) {
                                    viewModel.onLocalBreadcrumbClicked(uiState.currentPath)
                                } else if (uiState.mode == SubtitleBrowserMode.BROWSING_REMOTE) {
                                    viewModel.onRemoteBreadcrumbClicked(uiState.currentPath)
                                } else {
                                    viewModel.loadLocalRoots()
                                }
                            }
                        )
                    }
                }
                uiState.mode == SubtitleBrowserMode.ROOTS -> {
                    RootsContent(
                        uiState = uiState,
                        onLocalRootClicked = viewModel::onStorageRootClicked,
                        onRemoteRootClicked = {
                            val server = uiState.remoteServer
                            val parent = uiState.remoteParentPath
                            if (server != null && parent != null) {
                                viewModel.browseRemoteDirectory(server, parent)
                            }
                        }
                    )
                }
                uiState.mode == SubtitleBrowserMode.BROWSING_LOCAL -> {
                    LocalBrowseContent(
                        items = uiState.localItems,
                        isEmpty = uiState.isEmpty,
                        onFolderClicked = viewModel::onLocalFolderClicked,
                        onFileClicked = { file ->
                            val fileUri = Uri.fromFile(File(file.path))
                            onSubtitleSelected(fileUri, file.name)
                        }
                    )
                }
                uiState.mode == SubtitleBrowserMode.BROWSING_REMOTE -> {
                    RemoteBrowseContent(
                        items = uiState.remoteItems,
                        isEmpty = uiState.isEmpty,
                        onFolderClicked = viewModel::onRemoteFolderClicked,
                        onFileClicked = { file ->
                            val fileUri = viewModel.buildRemotePlaybackUri(file.path)
                            if (fileUri != null) {
                                onSubtitleSelected(fileUri, file.name)
                            }
                        }
                    )
                }
            }
        }
    }
}

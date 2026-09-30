package com.rhnxdev.hzplayer.presentation.history

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.History
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.AlbumArtImage
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.CornerRadii
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.domain.model.PlayHistoryItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun HistoryScreen(
    onBack: () -> Unit,
    onPlay: (PlayHistoryItem) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: HistoryViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    HistoryContent(
        state = uiState,
        onBack = onBack,
        onPlay = onPlay,
        onClearAll = viewModel::onClearAll,
        modifier = modifier,
    )
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun HistoryContent(
    state: HistoryUiState,
    onBack: () -> Unit,
    onPlay: (PlayHistoryItem) -> Unit,
    onClearAll: () -> Unit,
    modifier: Modifier = Modifier,
) {
    var showClearDialog by remember { mutableStateOf(false) }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.history_title)) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.back_cd),
                        )
                    }
                },
                actions = {
                    if (state.items.isNotEmpty()) {
                        IconButton(onClick = { showClearDialog = true }) {
                            Icon(
                                imageVector = Icons.Default.Delete,
                                contentDescription = stringResource(R.string.history_clear_all_cd),
                            )
                        }
                    }
                },
            )
        },
    ) { innerPadding ->
        if (state.items.isEmpty() && !state.isLoading) {
            MediaEmptyState(
                icon = Icons.Default.History,
                title = stringResource(R.string.history_empty_title),
                subtitle = stringResource(R.string.history_empty_subtitle),
                modifier = Modifier
                    .fillMaxSize()
                    .padding(innerPadding),
            )
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    .padding(innerPadding),
            ) {
                items(state.items, key = { it.uri }) { item ->
                    HistoryRow(
                        item = item,
                        onPlay = { onPlay(item) },
                    )
                }
            }
        }
    }

    if (showClearDialog) {
        AlertDialog(
            onDismissRequest = { showClearDialog = false },
            containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
            icon = {
                Icon(
                    imageVector = Icons.Default.Delete,
                    contentDescription = null,
                )
            },
            title = { Text(stringResource(R.string.history_clear_dialog_title)) },
            text = { Text(stringResource(R.string.history_clear_dialog_message)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        showClearDialog = false
                        onClearAll()
                    },
                ) {
                    Text(stringResource(R.string.history_clear_dialog_confirm))
                }
            },
            dismissButton = {
                TextButton(onClick = { showClearDialog = false }) {
                    Text(stringResource(R.string.dialog_cancel))
                }
            },
        )
    }
}

/** Seamless, borderless row — no card, no divider — so the list reads as one surface. */
@Composable
private fun HistoryRow(
    item: PlayHistoryItem,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val clipShape = remember { RoundedCornerShape(CornerRadii.xs) }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clickable(onClick = onPlay)
            .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .run {
                    if (item.isVideo) size(width = 78.dp, height = 56.dp) else size(56.dp)
                }
                .clip(clipShape),
        ) {
            if (item.isVideo) {
                SubcomposeAsyncImage(
                    model = VideoFrame(item.uri, 0L),
                    contentDescription = item.title,
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.Crop,
                    error = { ThumbnailPlaceholder(mediaType = MediaType.VIDEO) },
                    loading = { ThumbnailPlaceholder(mediaType = MediaType.VIDEO) },
                )
            } else {
                AlbumArtImage(
                    albumArtUri = item.thumbnailUri,
                    contentDescription = item.title,
                )
            }
        }

        Spacer(modifier = Modifier.width(Spacing.md))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = item.title,
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                text = item.artist ?: stringResource(
                    if (item.isVideo) R.string.history_kind_video else R.string.history_kind_audio,
                ),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun HistoryContentPreview() {
    HzPlayerTheme {
        HistoryContent(
            state = HistoryUiState(
                isLoading = false,
                items = listOf(
                    PlayHistoryItem(
                        uri = "file:///video1.mp4",
                        title = "Big Buck Bunny",
                        isVideo = true,
                        playedAt = 0L,
                    ),
                    PlayHistoryItem(
                        uri = "file:///song1.mp3",
                        title = "Nightcall",
                        isVideo = false,
                        playedAt = 0L,
                        artist = "Kavinsky",
                    ),
                ),
            ),
            onBack = {},
            onPlay = {},
            onClearAll = {},
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun HistoryEmptyPreview() {
    HzPlayerTheme {
        HistoryContent(
            state = HistoryUiState(isLoading = false),
            onBack = {},
            onPlay = {},
            onClearAll = {},
        )
    }
}

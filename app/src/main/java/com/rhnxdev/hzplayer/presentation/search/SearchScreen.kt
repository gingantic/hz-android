package com.rhnxdev.hzplayer.presentation.search

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.core.components.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onVideoClicked: (VideoItem) -> Unit = {},
    onAudioClicked: (AudioItem, List<AudioItem>) -> Unit = { _, _ -> },
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Search input
        OutlinedTextField(
            value = uiState.query,
            onValueChange = viewModel::onQueryChanged,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg, vertical = Spacing.sm),
            placeholder = {
                Text(
                    text = "Search media...",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = "Search",
                )
            },
            trailingIcon = {
                if (uiState.query.isNotBlank()) {
                    IconButton(onClick = viewModel::onClearQuery) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = "Clear",
                        )
                    }
                }
            },
            singleLine = true,
            shape = MaterialTheme.shapes.medium,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary,
                unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant,
            ),
        )

        // Results or empty state
        when {
            uiState.isSearching -> {
                MediaLoadingState(
                    itemCount = 4,
                    shape = ShimmerShape.LIST_ITEM,
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.hasSearched && uiState.videoResults.isEmpty() && uiState.audioResults.isEmpty() -> {
                MediaEmptyState(
                    icon = Icons.Default.Search,
                    title = "No results",
                    subtitle = "No media found for \"${uiState.query}\".",
                    modifier = Modifier.fillMaxSize(),
                )
            }

            uiState.hasSearched || uiState.query.isNotBlank() -> {
                SearchResults(
                    uiState = uiState,
                    onVideoClicked = { video ->
                        onVideoClicked(video)
                    },
                    onAudioClicked = { audio ->
                        onAudioClicked(audio, uiState.audioResults)
                    },
                )
            }

            else -> {
                MediaEmptyState(
                    icon = Icons.Default.Search,
                    title = "Search your media",
                    subtitle = "Find videos and music across your device.",
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
    }
}

@Composable
private fun SearchResults(
    uiState: SearchUiState,
    onVideoClicked: (VideoItem) -> Unit = {},
    onAudioClicked: (AudioItem) -> Unit = {},
    modifier: Modifier = Modifier,
) {
    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = Spacing.xxl),
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        // Video results
        if (uiState.videoResults.isNotEmpty()) {
            item {
                Text(
                    text = "Videos (${uiState.videoResults.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        top = Spacing.sm,
                        bottom = Spacing.xs,
                    ),
                )
            }
            items(uiState.videoResults, key = { "v_${it.id}" }) { video ->
                MediaListItem(
                    title = video.title,
                    subtitle = video.resolution ?: "Video",
                    durationMs = video.durationMs,
                    thumbnailContent = {
                        ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                    },
                    onClick = { onVideoClicked(video) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        // Audio results
        if (uiState.audioResults.isNotEmpty()) {
            item {
                Text(
                    text = "Music (${uiState.audioResults.size})",
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.xs,
                    ),
                )
            }
            items(uiState.audioResults, key = { "a_${it.id}" }) { song ->
                MediaListItem(
                    title = song.title,
                    subtitle = buildString {
                        song.artist?.let { append(it) }
                        song.album?.let { if (isNotEmpty()) append(" • "); append(it) }
                    },
                    durationMs = song.durationMs,
                    thumbnailContent = {
                        ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                    },
                    onClick = { onAudioClicked(song) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SearchScreenPreview() {
    HzPlayerTheme {
        SearchScreen(
            viewModel = hiltViewModel(),
        )
    }
}

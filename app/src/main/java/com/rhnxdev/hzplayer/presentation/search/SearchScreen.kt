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
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.core.components.MediaEmptyState
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.MediaLoadingState
import com.rhnxdev.hzplayer.core.components.ShimmerShape
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import com.rhnxdev.hzplayer.core.thumbnail.VideoFrame

@Composable
fun SearchScreen(
    viewModel: SearchViewModel = hiltViewModel(),
    onVideoClicked: (VideoItem) -> Unit = {},
    onAudioClicked: (AudioItem, List<AudioItem>) -> Unit = { _, _ -> },
    onAlbumClicked: (Album) -> Unit = {},
    onArtistClicked: (Artist) -> Unit = {},
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
                    text = stringResource(R.string.search_placeholder),
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            leadingIcon = {
                Icon(
                    imageVector = Icons.Default.Search,
                    contentDescription = stringResource(R.string.search_cd),
                )
            },
            trailingIcon = {
                if (uiState.query.isNotBlank()) {
                    IconButton(onClick = viewModel::onClearQuery) {
                        Icon(
                            imageVector = Icons.Default.Clear,
                            contentDescription = stringResource(R.string.clear_cd),
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

            uiState.hasSearched && uiState.videoResults.isEmpty() && uiState.audioResults.isEmpty()
                && uiState.albumResults.isEmpty() && uiState.artistResults.isEmpty() -> {
                MediaEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.no_results_title),
                    subtitle = stringResource(R.string.search_empty, uiState.query),
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
                    onAlbumClicked = onAlbumClicked,
                    onArtistClicked = onArtistClicked,
                )
            }

            else -> {
                MediaEmptyState(
                    icon = Icons.Default.Search,
                    title = stringResource(R.string.search_empty_title),
                    subtitle = stringResource(R.string.search_empty_subtitle),
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
    onAlbumClicked: (Album) -> Unit = {},
    onArtistClicked: (Artist) -> Unit = {},
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
                    text = stringResource(R.string.search_videos_count, uiState.videoResults.size),
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
                        SubcomposeAsyncImage(
                            model = VideoFrame(video.uri, video.dateModified),
                            contentDescription = video.title,
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop,
                            error = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            },
                            loading = {
                                ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
                            }
                        )
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
                    text = stringResource(R.string.search_music_count, uiState.audioResults.size),
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
                    isSquareThumbnail = true,
                    thumbnailContent = {
                        if (song.albumArtUri != null) {
                            SubcomposeAsyncImage(
                                model = song.albumArtUri,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                },
                                loading = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                }
                            )
                        } else {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        }
                    },
                    onClick = { onAudioClicked(song) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        // Album results
        if (uiState.albumResults.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.search_albums_count, uiState.albumResults.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.xs,
                    ),
                )
            }
            items(uiState.albumResults, key = { "al_${it.id}" }) { album ->
                val albumSubtitle = buildString {
                    album.artist?.let { append(it) }
                    if (album.trackCount > 0) {
                        if (isNotEmpty()) append(" • ")
                        append("${album.trackCount} songs")
                    }
                }
                MediaListItem(
                    title = album.title,
                    subtitle = albumSubtitle,
                    isSquareThumbnail = true,
                    thumbnailContent = {
                        if (album.albumArtUri != null) {
                            SubcomposeAsyncImage(
                                model = album.albumArtUri,
                                contentDescription = album.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                },
                                loading = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                }
                            )
                        } else {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        }
                    },
                    onClick = { onAlbumClicked(album) },
                    modifier = Modifier.padding(horizontal = Spacing.lg),
                )
            }
        }

        // Artist results
        if (uiState.artistResults.isNotEmpty()) {
            item {
                Text(
                    text = stringResource(R.string.search_artists_count, uiState.artistResults.size),
                    style = MaterialTheme.typography.titleSmall,
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(
                        start = Spacing.lg,
                        top = Spacing.lg,
                        bottom = Spacing.xs,
                    ),
                )
            }
            items(uiState.artistResults, key = { "ar_${it.id}" }) { artist ->
                MediaListItem(
                    title = artist.name,
                    subtitle = stringResource(R.string.artist_subtitle, artist.albumCount, artist.trackCount),
                    isSquareThumbnail = true,
                    thumbnailContent = {
                        if (artist.albumArtUri != null) {
                            SubcomposeAsyncImage(
                                model = artist.albumArtUri,
                                contentDescription = artist.name,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                },
                                loading = {
                                    ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                                }
                            )
                        } else {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        }
                    },
                    onClick = { onArtistClicked(artist) },
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

package com.rhnxdev.hzplayer.presentation.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.presentation.audio.components.AlbumCard
import com.rhnxdev.hzplayer.presentation.audio.components.AudioDetailHeader

@Composable
fun ArtistDetailScreen(
    artistName: String,
    onBack: () -> Unit,
    onSongPlayed: () -> Unit,
    onAlbumClicked: (String) -> Unit,
    viewModel: AudioDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(artistName) { viewModel.loadArtist(artistName) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        HzPlayerTopBar(title = uiState.title.ifEmpty { artistName }, showBack = true, onBack = onBack, marqueeTitle = true)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                AudioDetailHeader(
                    title = uiState.title.ifEmpty { artistName },
                    subtitle = uiState.subtitle,
                    albumArtUri = uiState.albumArtUri,
                    circleArt = true,
                    onPlay = viewModel::onPlay,
                    onShuffle = viewModel::onShuffle,
                )
            }

            if (uiState.albums.isNotEmpty()) {
                item {
                    SectionLabel(stringResource(R.string.albums))
                    LazyRow(
                        modifier = Modifier.fillMaxWidth(),
                        contentPadding = PaddingValues(vertical = Spacing.sm),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        items(uiState.albums, key = { it.id }) { album ->
                            AlbumCard(
                                title = album.title,
                                artist = album.artist,
                                trackCount = album.trackCount,
                                albumArtUri = album.albumArtUri,
                                onClick = { onAlbumClicked(album.title) },
                                modifier = Modifier.width(150.dp),
                            )
                        }
                    }
                }
            }

            item { SectionLabel(stringResource(R.string.songs)) }

            itemsIndexed(uiState.songs, key = { _, s -> s.id }) { index, song ->
                MediaListItem(
                    title = song.title,
                    subtitle = song.album ?: "",
                    durationMs = song.durationMs,
                    isSquareThumbnail = true,
                    thumbnailContent = {
                        if (song.albumArtUri != null) {
                            SubcomposeAsyncImage(
                                model = song.albumArtUri,
                                contentDescription = song.title,
                                modifier = Modifier.fillMaxSize(),
                                contentScale = ContentScale.Crop,
                                error = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
                                loading = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
                            )
                        } else {
                            ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                        }
                    },
                    onClick = {
                        viewModel.onSongClicked(index)
                        onSongPlayed()
                    },
                )
            }
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(vertical = Spacing.xs),
    )
}

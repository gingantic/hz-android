package com.rhnxdev.hzplayer.presentation.audio

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.components.MediaListItem
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.presentation.audio.components.AudioDetailHeader

@Composable
fun AlbumDetailScreen(
    albumTitle: String,
    onBack: () -> Unit,
    onSongPlayed: () -> Unit,
    viewModel: AudioDetailViewModel = hiltViewModel(),
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    LaunchedEffect(albumTitle) { viewModel.loadAlbum(albumTitle) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .systemBarsPadding(),
    ) {
        HzPlayerTopBar(title = uiState.title.ifEmpty { albumTitle }, showBack = true, onBack = onBack, marqueeTitle = true)

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = Spacing.lg, vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(2.dp),
        ) {
            item {
                AudioDetailHeader(
                    title = uiState.title.ifEmpty { albumTitle },
                    subtitle = uiState.subtitle,
                    albumArtUri = uiState.albumArtUri,
                    circleArt = false,
                    onPlay = viewModel::onPlay,
                    onShuffle = viewModel::onShuffle,
                )
            }
            itemsIndexed(uiState.songs, key = { _, s -> s.id }) { index, song ->
                MediaListItem(
                    title = song.title,
                    subtitle = song.artist ?: "",
                    durationMs = song.durationMs,
                    isSquareThumbnail = true,
                    thumbnailContent = {
                        TrackNumber(song.trackNumber, song.albumArtUri, song.title)
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

/** Album rows show the track number instead of per-row art (art is in the header). */
@Composable
private fun TrackNumber(trackNumber: Int, albumArtUri: String?, title: String) {
    if (trackNumber > 0) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = trackNumber.toString(),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    } else if (albumArtUri != null) {
        SubcomposeAsyncImage(
            model = albumArtUri,
            contentDescription = title,
            modifier = Modifier.fillMaxSize(),
            contentScale = ContentScale.Crop,
            error = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
            loading = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO) },
        )
    } else {
        ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
    }
}

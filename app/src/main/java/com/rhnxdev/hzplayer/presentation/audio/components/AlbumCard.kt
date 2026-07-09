package com.rhnxdev.hzplayer.presentation.audio.components

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale

@Composable
fun AlbumCard(
    title: String,
    artist: String?,
    trackCount: Int,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    albumArtUri: String? = null,
) {
    Card(
        onClick = onClick,
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(Spacing.sm),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
    ) {
        Column(
            modifier = Modifier.padding(Spacing.sm),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Square album art
            if (albumArtUri != null) {
                SubcomposeAsyncImage(
                    model = albumArtUri,
                    contentDescription = title,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Spacing.sm)),
                    contentScale = ContentScale.Crop,
                    error = {
                        ThumbnailPlaceholder(
                            mediaType = MediaType.AUDIO,
                            modifier = Modifier.fillMaxSize(),
                        )
                    },
                    loading = {
                        ThumbnailPlaceholder(
                            mediaType = MediaType.AUDIO,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                )
            } else {
                ThumbnailPlaceholder(
                    mediaType = MediaType.AUDIO,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                        .clip(RoundedCornerShape(Spacing.sm)),
                )
            }

            Spacer(modifier = Modifier.height(Spacing.sm))

            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )

            if (artist != null) {
                Text(
                    text = artist,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Text(
                text = "$trackCount songs",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun AlbumCardPreview() {
    HzPlayerTheme {
        AlbumCard(
            title = "Random Access Memories",
            artist = "Daft Punk",
            trackCount = 13,
            onClick = {},
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

package com.rhnxdev.hzplayer.presentation.audio.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil3.compose.SubcomposeAsyncImage
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.MediaType

/**
 * Shared header for album / artist detail: large art, title, subtitle, and
 * Play + Shuffle actions. [circleArt] draws the art as a circle (artist) vs a
 * rounded square (album).
 */
@Composable
fun AudioDetailHeader(
    title: String,
    subtitle: String,
    albumArtUri: String?,
    circleArt: Boolean,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val artShape = if (circleArt) CircleShape else RoundedCornerShape(Spacing.md)
    Column(
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.lg, vertical = Spacing.md),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        val artModifier = Modifier
            .fillMaxWidth(0.6f)
            .aspectRatio(1f)
            .clip(artShape)
        if (albumArtUri != null) {
            SubcomposeAsyncImage(
                model = albumArtUri,
                contentDescription = title,
                modifier = artModifier,
                contentScale = ContentScale.Crop,
                error = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO, modifier = Modifier.fillMaxSize()) },
                loading = { ThumbnailPlaceholder(mediaType = MediaType.AUDIO, modifier = Modifier.fillMaxSize()) },
            )
        } else {
            ThumbnailPlaceholder(mediaType = MediaType.AUDIO, modifier = artModifier)
        }

        Spacer(modifier = Modifier.height(Spacing.md))

        Text(
            text = title,
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
            textAlign = TextAlign.Center,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            text = subtitle,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(modifier = Modifier.height(Spacing.md))

        Row(horizontalArrangement = Arrangement.spacedBy(Spacing.sm)) {
            Button(onClick = onPlay) {
                Icon(Icons.Filled.PlayArrow, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(stringResource(R.string.play))
            }
            OutlinedButton(onClick = onShuffle) {
                Icon(Icons.Filled.Shuffle, contentDescription = null)
                Spacer(modifier = Modifier.padding(horizontal = 2.dp))
                Text(stringResource(R.string.shuffle))
            }
        }
    }
}

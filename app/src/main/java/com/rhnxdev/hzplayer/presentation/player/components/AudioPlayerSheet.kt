package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.foundation.basicMarquee
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import coil3.compose.SubcomposeAsyncImage
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun AudioPlayerSheet(
    uiState: PlayerUiState,
    title: String?,
    artist: String?,
    onPlayPause: () -> Unit,
    onSkipNext: () -> Unit,
    onSkipPrevious: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onToggleShuffle: () -> Unit,
    onCycleRepeat: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    if (isLandscape) {
        Row(
            modifier = modifier,
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(Spacing.xl)
        ) {
            // Left side: Album art
            Box(
                modifier = Modifier
                    .fillMaxHeight(0.7f)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val artModel = uiState.currentArtworkUri ?: uiState.currentPlaybackUri
                if (artModel != null) {
                    SubcomposeAsyncImage(
                        model = artModel,
                        contentDescription = title,
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
            }

            // Right side: Track info, seekbar, controls
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight(),
                verticalArrangement = Arrangement.SpaceEvenly,
                horizontalAlignment = Alignment.Start
            ) {
                // Track info
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(64.dp),
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(
                        text = title ?: "No track",
                        style = MaterialTheme.typography.titleLarge.copy(
                            fontWeight = FontWeight.Bold,
                        ),
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = artist ?: "Unknown artist",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }

                Spacer(modifier = Modifier.height(Spacing.sm))

                // Seekbar
                PlayerSeekBar(
                    currentPosition = uiState.currentPosition,
                    duration = uiState.duration,
                    bufferedPercentage = uiState.bufferedPercentage,
                    onSeek = onSeekTo,
                    onSeekStart = {},
                    onSeekEnd = {},
                )

                Spacer(modifier = Modifier.height(Spacing.md))

                // Controls row including shuffle/repeat
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onToggleShuffle) {
                        Icon(
                            imageVector = Icons.Default.Shuffle,
                            contentDescription = "Shuffle",
                            tint = if (uiState.shuffleMode) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }

                    IconButton(onClick = onSkipPrevious) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = "Previous",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    // Large Play/Pause button
                    val playBtnShape = remember { RoundedCornerShape(50) }
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier
                            .size(64.dp)
                            .background(
                                color = MaterialTheme.colorScheme.primary,
                                shape = playBtnShape,
                            ),
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                            tint = MaterialTheme.colorScheme.onPrimary,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    IconButton(onClick = onSkipNext) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = "Next",
                            tint = MaterialTheme.colorScheme.onSurface,
                            modifier = Modifier.size(32.dp),
                        )
                    }

                    IconButton(onClick = onCycleRepeat) {
                        Icon(
                            imageVector = when (uiState.repeatMode) {
                                RepeatMode.ONE -> Icons.Default.RepeatOne
                                else -> Icons.Default.Repeat
                            },
                            contentDescription = "Repeat",
                            tint = if (uiState.repeatMode != RepeatMode.NONE) MaterialTheme.colorScheme.primary
                            else MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }
        }
    } else {
        Column(
            modifier = modifier,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Spacer(modifier = Modifier.height(Spacing.xl))

            // Album art — max width with proper padding
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = Spacing.sm)
                    .aspectRatio(1f)
                    .clip(RoundedCornerShape(20.dp)),
                contentAlignment = Alignment.Center,
            ) {
                val artModel = uiState.currentArtworkUri ?: uiState.currentPlaybackUri
                if (artModel != null) {
                    SubcomposeAsyncImage(
                        model = artModel,
                        contentDescription = title,
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
            }

            Spacer(modifier = Modifier.height(Spacing.xl))

            // Track info — left-aligned for Spotify feel
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(64.dp),
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = title ?: "No track",
                    style = MaterialTheme.typography.headlineSmall.copy(
                        fontWeight = FontWeight.Bold,
                        lineHeight = 28.sp,
                    ),
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = 1,
                    modifier = Modifier.basicMarquee(),
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = artist ?: "Unknown artist",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Seekbar
            PlayerSeekBar(
                currentPosition = uiState.currentPosition,
                duration = uiState.duration,
                bufferedPercentage = uiState.bufferedPercentage,
                onSeek = onSeekTo,
                onSeekStart = {},
                onSeekEnd = {},
            )

            Spacer(modifier = Modifier.height(Spacing.lg))

            // Main playback controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onSkipPrevious) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }

                // Large Play/Pause button
                val playBtnShape = remember { RoundedCornerShape(50) }
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier
                        .size(72.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = playBtnShape,
                        ),
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = MaterialTheme.colorScheme.onPrimary,
                        modifier = Modifier.size(36.dp),
                    )
                }

                IconButton(onClick = onSkipNext) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.size(32.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.height(Spacing.md))

            // Secondary controls — shuffle & repeat (smaller, below main controls)
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                IconButton(onClick = onToggleShuffle) {
                    Icon(
                        imageVector = Icons.Default.Shuffle,
                        contentDescription = "Shuffle",
                        tint = if (uiState.shuffleMode) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }

                // Spacer to balance the layout (center the main controls, place secondary on sides)
                Spacer(modifier = Modifier.width(72.dp))

                IconButton(onClick = onCycleRepeat) {
                    Icon(
                        imageVector = when (uiState.repeatMode) {
                            RepeatMode.ONE -> Icons.Default.RepeatOne
                            else -> Icons.Default.Repeat
                        },
                        contentDescription = "Repeat",
                        tint = if (uiState.repeatMode != RepeatMode.NONE) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }

            Spacer(modifier = Modifier.weight(1f))
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun AudioPlayerSheetPreview() {
    HzPlayerTheme {
        AudioPlayerSheet(
            uiState = PlayerUiState(
                isPlaying = true,
                currentPosition = 180000,
                duration = 369000,
            ),
            title = "Get Lucky",
            artist = "Daft Punk",
            onPlayPause = {},
            onSkipNext = {},
            onSkipPrevious = {},
            onSeekTo = {},
            onToggleShuffle = {},
            onCycleRepeat = {},
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface),
        )
    }
}

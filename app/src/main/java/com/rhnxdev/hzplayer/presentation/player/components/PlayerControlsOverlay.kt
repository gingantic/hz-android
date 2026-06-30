package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun PlayerControlsOverlay(
    uiState: PlayerUiState,
    title: String?,
    onBack: () -> Unit,
    onPlayPause: () -> Unit,
    onSeekTo: (Long) -> Unit,
    onSkipForward: () -> Unit,
    onSkipBackward: () -> Unit,
    onSpeedClick: () -> Unit,
    onSubtitleClick: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val overlayBg = remember { Color.Black.copy(alpha = 0.6f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .background(overlayBg),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
        ) {
            // Top bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(Spacing.sm),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = onBack) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "Back",
                        tint = Color.White,
                    )
                }
                Text(
                    text = title ?: "Now Playing",
                    style = MaterialTheme.typography.titleMedium,
                    color = Color.White,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.weight(1f),
                )
            }

            // Spacer pushes center controls to middle
            Spacer(modifier = Modifier.weight(1f))

            // Center controls
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Skip backward 10s
                IconButton(
                    onClick = onSkipBackward,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = "Replay 10",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                // Previous
                IconButton(
                    onClick = { /* previous track */ },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipPrevious,
                        contentDescription = "Previous",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                // Play/Pause (larger)
                IconButton(
                    onClick = onPlayPause,
                    modifier = Modifier.size(72.dp),
                ) {
                    Icon(
                        imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                        tint = Color.White,
                        modifier = Modifier.size(48.dp),
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                // Next
                IconButton(
                    onClick = { /* next track */ },
                    modifier = Modifier.size(48.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.SkipNext,
                        contentDescription = "Next",
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                // Skip forward 10s
                IconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(56.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = "Forward 10",
                        tint = Color.White,
                        modifier = Modifier.size(36.dp),
                    )
                }
            }

            // Bottom bar with seekbar
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = Spacing.lg),
            ) {
                PlayerSeekBar(
                    currentPosition = uiState.currentPosition,
                    duration = uiState.duration,
                    bufferedPercentage = uiState.bufferedPercentage,
                    onSeek = onSeekTo,
                    onSeekStart = {},
                    onSeekEnd = {},
                )

                // Bottom row: speed, extra buttons
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = Spacing.lg),
                    horizontalArrangement = Arrangement.SpaceBetween,
                ) {
                    // Speed
                    IconButton(onClick = onSpeedClick) {
                        Text(
                            text = "${uiState.playbackSpeed}x",
                            color = Color.White,
                            fontSize = 13.sp,
                            fontWeight = FontWeight.Medium,
                        )
                    }

                    Row {
                        // Subtitles
                        IconButton(onClick = onSubtitleClick) {
                            Text(
                                text = "CC",
                                color = Color.White,
                                fontSize = 13.sp,
                                fontWeight = FontWeight.Bold,
                            )
                        }
                    }
                }
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun PlayerControlsOverlayPreview() {
    HzPlayerTheme {
        Box(modifier = Modifier.size(400.dp, 300.dp)) {
            PlayerControlsOverlay(
                uiState = PlayerUiState(
                    isPlaying = true,
                    currentPosition = 180000,
                    duration = 369000,
                ),
                title = "Get Lucky - Daft Punk",
                onBack = {},
                onPlayPause = {},
                onSeekTo = {},
                onSkipForward = {},
                onSkipBackward = {},
                onSpeedClick = {},
                onSubtitleClick = {},
            )
        }
    }
}

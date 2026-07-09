package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Forward10
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.LockOpen
import androidx.compose.material.icons.filled.MusicNote
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.PlaylistPlay
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.ScreenRotationAlt
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Subtitles
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarPaddingValues
import com.rhnxdev.hzplayer.core.designsystem.stableStatusBarTopDp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

private val topGradient = Brush.verticalGradient(
    colors = listOf(Color.Black.copy(alpha = 0.7f), Color.Transparent),
)

private val bottomGradient = Brush.verticalGradient(
    colors = listOf(Color.Transparent, Color.Black.copy(alpha = 0.7f)),
)

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
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
    onAudioClick: () -> Unit = {},
    onSubtitleClick: () -> Unit,
    onLockClick: () -> Unit = {},
    onAspectRatioClick: () -> Unit = {},
    onOrientationClick: () -> Unit = {},
    onPlaylistClick: () -> Unit = {},
    onDebugClick: (() -> Unit)? = null,
    onSkipToNext: (() -> Unit)? = null,
    onSkipToPrevious: (() -> Unit)? = null,
    onInteract: () -> Unit = {},
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize().pointerInput(Unit) {
            awaitPointerEventScope {
                awaitPointerEvent()
                onInteract()
            }
        },
    ) {
        // ── Top bar: back + title + network speed ──
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.TopCenter)
                .background(topGradient)
                .windowInsetsPadding(
                    WindowInsets.navigationBarsIgnoringVisibility
                        .only(WindowInsetsSides.Horizontal)
                )
                .padding(top = stableStatusBarTopDp())
                .padding(horizontal = Spacing.xs, vertical = Spacing.xs),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = stringResource(R.string.back),
                    tint = Color.White,
                    modifier = Modifier.size(26.dp),
                )
            }

            Text(
                text = title ?: stringResource(R.string.now_playing),
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White,
                fontWeight = FontWeight.Medium,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = Spacing.xs),
            )

            // Network speed indicator — only for remote streams, not local files
            if (uiState.currentPlaybackUri?.let { isRemoteUri(it) } == true) {
                NetworkSpeedChip(uiState.networkTraffic)
            }

            Spacer(modifier = Modifier.width(Spacing.xs))

            // Debug (Stats for nerds) — only when debugMode enabled
            if (onDebugClick != null) {
                Text(
                    text = stringResource(R.string.stats),
                    color = Color.White.copy(alpha = 0.7f),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onDebugClick)
                        .background(Color(0x33FFFFFF))
                        .padding(horizontal = 8.dp, vertical = 5.dp),
                )
                Spacer(modifier = Modifier.width(4.dp))
            }

            // Lock
            IconButton(onClick = onLockClick) {
                Icon(
                    imageVector = if (uiState.playerLocked) Icons.Default.Lock else Icons.Default.LockOpen,
                    contentDescription = stringResource(if (uiState.playerLocked) R.string.unlock else R.string.lock),
                    tint = Color.White.copy(alpha = 0.7f),
                    modifier = Modifier.size(24.dp),
                )
            }

            Spacer(modifier = Modifier.width(4.dp))

            // Aspect ratio
            Text(
                text = uiState.aspectRatioMode.label,
                color = Color.White.copy(alpha = 0.8f),
                fontSize = 11.sp,
                fontWeight = FontWeight.Medium,
                modifier = Modifier
                    .clip(CircleShape)
                    .clickable(onClick = onAspectRatioClick)
                    .background(Color.White.copy(alpha = 0.12f))
                    .padding(horizontal = 8.dp, vertical = 5.dp),
            )

            // Playlist toggle
            if (uiState.videoPlaylist.size > 1) {
                Spacer(modifier = Modifier.width(4.dp))
                IconButton(onClick = onPlaylistClick) {
                    Icon(
                        imageVector = Icons.Default.PlaylistPlay,
                        contentDescription = stringResource(R.string.playlist),
                        tint = if (uiState.showPlaylistDrawer) Color(0xFF4CAF50) else Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }

        // ── Bottom panel: seekbar + play controls + secondary row ──
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .align(Alignment.BottomCenter)
                .background(bottomGradient)
                .padding(stableNavBarPaddingValues())
                .padding(bottom = 8.dp),
        ) {
            // Row 1: seekbar
            PlayerSeekBar(
                currentPosition = uiState.currentPosition,
                duration = uiState.duration,
                bufferedPercentage = uiState.bufferedPercentage,
                onSeek = onSeekTo,
                onSeekStart = {},
                onSeekEnd = {},
            )

            // Row 2: play controls
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (onSkipToPrevious != null) {
                    IconButton(
                        onClick = onSkipToPrevious,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipPrevious,
                            contentDescription = stringResource(R.string.previous),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                    Spacer(modifier = Modifier.width(Spacing.md))
                }

                IconButton(
                    onClick = onSkipBackward,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Replay10,
                        contentDescription = stringResource(R.string.replay_10s),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                Box(
                    modifier = Modifier
                        .size(56.dp)
                        .clip(CircleShape)
                        .background(Color.Black.copy(alpha = 0.35f)),
                    contentAlignment = Alignment.Center,
                ) {
                    IconButton(
                        onClick = onPlayPause,
                        modifier = Modifier.size(56.dp),
                    ) {
                        Icon(
                            imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                            contentDescription = stringResource(if (uiState.isPlaying) R.string.pause else R.string.play),
                            tint = Color.White,
                            modifier = Modifier.size(36.dp),
                        )
                    }
                }

                Spacer(modifier = Modifier.width(Spacing.lg))

                IconButton(
                    onClick = onSkipForward,
                    modifier = Modifier.size(44.dp),
                ) {
                    Icon(
                        imageVector = Icons.Default.Forward10,
                        contentDescription = stringResource(R.string.forward_10s),
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                }

                if (onSkipToNext != null) {
                    Spacer(modifier = Modifier.width(Spacing.md))
                    IconButton(
                        onClick = onSkipToNext,
                        modifier = Modifier.size(40.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Default.SkipNext,
                            contentDescription = stringResource(R.string.next),
                            tint = Color.White,
                            modifier = Modifier.size(24.dp),
                        )
                    }
                }
            }

            // Row 3: secondary controls (icon-only, evenly spaced)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 6.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // Speed
                Text(
                    text = stringResource(R.string.speed_value, uiState.playbackSpeed),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier
                        .clip(CircleShape)
                        .clickable(onClick = onSpeedClick)
                        .background(Color.White.copy(alpha = 0.12f))
                        .padding(horizontal = 8.dp, vertical = 4.dp),
                )

                // Audio
                IconButton(onClick = onAudioClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.MusicNote,
                        contentDescription = stringResource(R.string.audio),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // CC
                IconButton(onClick = onSubtitleClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.Subtitles,
                        contentDescription = stringResource(R.string.subtitles),
                        tint = Color.White,
                        modifier = Modifier.size(22.dp),
                    )
                }

                // Orientation
                IconButton(onClick = onOrientationClick, modifier = Modifier.size(40.dp)) {
                    Icon(
                        imageVector = Icons.Default.ScreenRotationAlt,
                        contentDescription = stringResource(R.string.orientation),
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(22.dp),
                    )
                }
            }
        }
    }
}

@Composable
private fun NetworkSpeedChip(traffic: NetworkTraffic) {
    Text(
        text = stringResource(R.string.network_speed_format, formatSpeed(traffic.speedDown)),
        color = Color.White.copy(alpha = 0.85f),
        fontSize = 11.sp,
        fontWeight = FontWeight.Medium,
        modifier = Modifier
            .clip(CircleShape)
            .background(Color.White.copy(alpha = 0.12f))
            .padding(horizontal = 8.dp, vertical = 4.dp),
    )
}

private fun formatSpeed(bytesPerSec: Long): String = when {
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
}

// ponytail: keep in sync with PlayerRepositoryImpl.startTrafficPolling
private fun isRemoteUri(uri: String): Boolean =
    uri.contains("://") && !uri.startsWith("file://") && !uri.startsWith("content://")

@PreviewLightDark
@Preview(widthDp = 640, heightDp = 320)
@Composable
private fun PlayerControlsOverlayPreview() {
    HzPlayerTheme {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.DarkGray),
        ) {
            PlayerControlsOverlay(
                uiState = PlayerUiState(
                    isPlaying = true,
                    currentPosition = 180000,
                    duration = 369000,
                ),
                title = "Blade Runner 2049 - Final Cut (2017)",
                onBack = {},
                onPlayPause = {},
                onSeekTo = {},
                onSkipForward = {},
                onSkipBackward = {},
                onSpeedClick = {},
                onSubtitleClick = {},
                onAspectRatioClick = {},
            )
        }
    }
}

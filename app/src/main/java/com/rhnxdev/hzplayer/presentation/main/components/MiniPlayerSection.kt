package com.rhnxdev.hzplayer.presentation.main.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.State
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.components.MiniPlayerBar

/**
 * Scoped mini-player composable — isolates player state collection so 250ms
 * position updates don't recompose the entire app tree.
 */
@Composable
fun MiniPlayerSection(
    playerViewModel: PlayerViewModel,
    isFullScreen: Boolean,
    onNavigateToPlayer: () -> Unit,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val currentPosition by playerViewModel.position.collectAsStateWithLifecycle()
    val progress: State<Float> = remember {
        derivedStateOf {
            if (playerState.duration > 0) {
                (currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
        }
    }
    MiniPlayerBar(
        title = playerState.currentTitle ?: "",
        subtitle = playerState.currentArtist ?: "",
        isPlaying = playerState.isPlaying,
        progress = progress,
        onPlayPause = { playerViewModel.onPlayPause() },
        onNext = { playerViewModel.onSkipNext() },
        onClick = onNavigateToPlayer,
        onDismiss = { playerViewModel.stop() },
        visible = playerState.currentTitle != null && !playerState.isVideo && !isFullScreen,
        artworkUri = playerState.currentArtworkUri,
    )
}

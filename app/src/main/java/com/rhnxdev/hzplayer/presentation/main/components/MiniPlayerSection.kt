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
    onNavigateToVideoPlayer: () -> Unit,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val currentPosition by playerViewModel.position.collectAsStateWithLifecycle()
    // When the floating window is enabled it owns video playback, so the mini bar
    // only handles audio then. When it is OFF, a backgrounded video session (e.g.
    // one started from the browser/URL and minimized) has no other surface, so the
    // mini bar must show it too — with a button back to the full-screen video.
    val floatingEnabled by playerViewModel.backgroundPlay.collectAsStateWithLifecycle()
    val progress: State<Float> = remember {
        derivedStateOf {
            if (playerState.duration > 0) {
                (currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
        }
    }
    // A video session is "backgrounded as audio" either explicitly ("Play as audio")
    // or implicitly (a video playing while no video surface is up and floating is off).
    val isBackgroundedVideo = playerState.playingVideoAsAudio ||
        (playerState.isVideo && !floatingEnabled)
    MiniPlayerBar(
        title = playerState.currentTitle ?: "",
        subtitle = playerState.currentArtist ?: "",
        isPlaying = playerState.isPlaying,
        progress = progress,
        onPlayPause = { playerViewModel.onPlayPause() },
        onNext = { playerViewModel.onSkipNext() },
        onClick = if (isBackgroundedVideo) onNavigateToVideoPlayerBound(playerViewModel, onNavigateToVideoPlayer) else onNavigateToPlayer,
        onDismiss = { playerViewModel.stop() },
        // Audio-only sessions, plus backgrounded video when the floating window is off.
        visible = playerState.currentTitle != null && !isFullScreen &&
            (!playerState.isVideo || isBackgroundedVideo),
        artworkUri = playerState.currentArtworkUri,
        // Any video session (explicit "Play as audio" or a backgrounded browser/URL
        // video) can be reopened full-screen — the engine keeps playing uninterrupted.
        onOpenAsVideo = if (isBackgroundedVideo) {
            {
                playerViewModel.onResumeAsVideo()
                onNavigateToVideoPlayer()
            }
        } else null,
    )
}

/**
 * Tapping the metadata of a backgrounded video also reopens it full-screen (same
 * as the dedicated button), binding the surface to the already-running engine.
 */
private fun onNavigateToVideoPlayerBound(
    playerViewModel: PlayerViewModel,
    onNavigateToVideoPlayer: () -> Unit,
): () -> Unit = {
    playerViewModel.onResumeAsVideo()
    onNavigateToVideoPlayer()
}

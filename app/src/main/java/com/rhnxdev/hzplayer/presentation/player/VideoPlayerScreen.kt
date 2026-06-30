package com.rhnxdev.hzplayer.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.ui.PlayerView
import com.rhnxdev.hzplayer.presentation.player.components.PlayerControlsOverlay
import com.rhnxdev.hzplayer.presentation.player.components.SeekIndicator
import kotlinx.coroutines.delay

/** Duration in ms for double-tap fixed seeks (left/right thirds). */
private const val TAP_SEEK_MS = 10_000L

@Composable
fun VideoPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current

    // --- Seek indicator local state ---
    var seekDelta by remember { mutableLongStateOf(0L) }
    var seekVisible by remember { mutableStateOf(false) }

    // Auto-hide the indicator after the last gesture
    LaunchedEffect(seekVisible) {
        if (seekVisible) {
            delay(1200)
            seekVisible = false
        }
    }

    // ----- Immersive fullscreen -----
    DisposableEffect(Unit) {
        val window = (view.context as? android.app.Activity)?.window
        if (window == null) return@DisposableEffect onDispose {}

        val originalNavColor = window.navigationBarColor
        val originalStatusColor = window.statusBarColor

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.Black.toArgb()
        window.statusBarColor = Color.Black.toArgb()

        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window.navigationBarColor = originalNavColor
            window.statusBarColor = originalStatusColor
            WindowCompat.setDecorFitsSystemWindows(window, true)
            WindowInsetsControllerCompat(window, view).show(
                WindowInsetsCompat.Type.systemBars()
            )
        }
    }

    // Sync system bars with controls visibility
    DisposableEffect(uiState.showControls) {
        val window = (view.context as? android.app.Activity)?.window ?: return@DisposableEffect onDispose {}
        val controller = WindowInsetsControllerCompat(window, view)

        if (uiState.showControls) {
            controller.show(WindowInsetsCompat.Type.systemBars())
        } else {
            controller.hide(WindowInsetsCompat.Type.systemBars())
        }

        onDispose {}
    }

    // Auto-hide controls after 3 seconds
    LaunchedEffect(uiState.showControls) {
        if (uiState.showControls) {
            delay(3000)
            viewModel.onHideControls()
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
    ) {
        // Video surface
        AndroidView(
            factory = { ctx ->
                PlayerView(ctx).also { playerView ->
                    playerView.player = viewModel.getExoPlayer()
                    playerView.useController = false
                }
            },
            modifier = Modifier.fillMaxSize(),
        )

        // Gesture overlay — double-tap, single-tap, horizontal drag seek
        Box(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            val third = size.width / 3f

                            when {
                                offset.x < third -> { // left third → rewind
                                    viewModel.onSeekBy(-TAP_SEEK_MS)
                                    seekDelta = -TAP_SEEK_MS
                                    seekVisible = true
                                }
                                offset.x > third * 2 -> { // right third → forward
                                    viewModel.onSeekBy(TAP_SEEK_MS)
                                    seekDelta = TAP_SEEK_MS
                                    seekVisible = true
                                }
                                else -> viewModel.onPlayPause() // center → play/pause
                            }
                        },
                        onTap = { _ ->
                            viewModel.onToggleControls()
                        },
                    )
                }
                .pointerInput(Unit) {
                    // Horizontal drag-to-seek (full screen width ≈ full duration)
                    var dragAccumulated = 0f

                    detectHorizontalDragGestures(
                        onDragStart = { dragAccumulated = 0f },
                        onHorizontalDrag = { change, dragAmount ->
                            change.consume()
                            dragAccumulated += dragAmount

                            val durationMs = uiState.duration.coerceAtLeast(1L)
                            val widthPx = size.width.coerceAtLeast(1)
                            val ratio = (dragAccumulated / widthPx).coerceIn(-1f, 1f)
                            seekDelta = (ratio * durationMs).toLong()
                            seekVisible = true
                        },
                        onDragEnd = {
                            if (seekDelta != 0L) viewModel.onSeekBy(seekDelta)
                        },
                        onDragCancel = { seekVisible = false; seekDelta = 0L },
                    )
                },
        ) {
            // Controls overlay
            if (uiState.showControls) {
                PlayerControlsOverlay(
                    uiState = uiState,
                    title = uiState.currentTitle,
                    onBack = onBack,
                    onPlayPause = viewModel::onPlayPause,
                    onSeekTo = viewModel::onSeekTo,
                    onSkipForward = viewModel::onSkipForward,
                    onSkipBackward = viewModel::onSkipBackward,
                    onSpeedClick = { /* show speed dialog */ },
                    onSubtitleClick = { /* show subtitle dialog */ },
                    modifier = Modifier.fillMaxSize().systemBarsPadding(),
                )
            }

            // Seek indicator (positioned on the correct side automatically)
            SeekIndicator(
                deltaMs = seekDelta,
                currentPositionMs = uiState.currentPosition,
                visible = seekVisible,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

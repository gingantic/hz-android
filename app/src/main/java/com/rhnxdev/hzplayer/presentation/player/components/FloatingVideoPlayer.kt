package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.player.PlayerSurface
import com.rhnxdev.hzplayer.presentation.player.PlayerUiState
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import kotlinx.coroutines.delay
import kotlin.math.roundToInt

/**
 * In-app floating mini player (YouTube-style). Renders a SECOND PlayerView bound
 * to the same singleton [PlayerViewModel] engine — ExoPlayer allows one Player on
 * multiple PlayerViews; only one renders video at a time, so the full-screen
 * surface detaches on minimize. Draggable; expand (top-left), play/pause
 * (bottom control bar), close (top-right).
 *
 * Mounted in the app shell's root Box so it floats over the tabs. Visibility is
 * gated by the caller (isVideo && floatingEnabled && !isFullScreen).
 */
@Composable
fun FloatingVideoPlayer(
    viewModel: PlayerViewModel,
    uiState: PlayerUiState,
    visible: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    if (!visible) return

    val position by viewModel.position.collectAsStateWithLifecycle()
    val progress = if (uiState.duration > 0) {
        (position.toFloat() / uiState.duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    // Drag offset; clamped to a sane range so it can't fly off-screen.
    var drag by remember { mutableStateOf(IntOffset(0, 0)) }

    // HUD Visibility State
    var showControls by remember { mutableStateOf(false) }

    // Auto-hide controls after 3 seconds of inactivity
    LaunchedEffect(showControls) {
        if (showControls) {
            delay(3000L)
            showControls = false
        }
    }

    val context = androidx.compose.ui.platform.LocalContext.current
    val activity = remember(context) { context as? android.app.Activity }
    var isInPip by remember { mutableStateOf(activity?.isInPictureInPictureMode == true) }

    DisposableEffect(activity) {
        val compAct = activity as? androidx.activity.ComponentActivity
        if (compAct != null) {
            val listener = androidx.core.util.Consumer<androidx.core.app.PictureInPictureModeChangedInfo> { info ->
                isInPip = info.isInPictureInPictureMode
            }
            compAct.addOnPictureInPictureModeChangedListener(listener)
            onDispose {
                compAct.removeOnPictureInPictureModeChangedListener(listener)
            }
        } else {
            onDispose {}
        }
    }

    val playerModifier = if (isInPip) {
        Modifier
            .fillMaxSize()
            .background(Color.Black)
    } else {
        modifier
            .offset { drag }
            .size(width = 280.dp, height = 158.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(Color.Black)
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = {
                        showControls = !showControls
                    }
                )
            }
            .pointerInput(Unit) {
                detectDragGestures { change, amount ->
                    change.consume()
                    drag = IntOffset(
                        x = (drag.x + amount.x.roundToInt()).coerceIn(-800, 800),
                        y = (drag.y + amount.y.roundToInt()).coerceIn(-1200, 1200),
                    )
                }
            }
    }

    Box(
        modifier = playerModifier,
    ) {
        // Video surface — second PlayerView on the shared engine.
        PlayerSurface(
            engine = viewModel.getActiveEngine(),
            uiState = uiState,
            modifier = Modifier.fillMaxSize(),
            onRenderView = {},
        )

        // Mini HUD overlay
        if (!isInPip) {
            AnimatedVisibility(
                visible = showControls,
                enter = fadeIn(),
                exit = fadeOut(),
                modifier = Modifier.fillMaxSize()
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.4f))
                ) {
                    // Expand (top-left) — opens full player.
                    IconButton(
                        onClick = onExpand,
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.TopStart),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Fullscreen,
                            contentDescription = "Expand",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    // Close (top-right)
                    IconButton(
                        onClick = onClose,
                        modifier = Modifier
                            .size(44.dp)
                            .align(Alignment.TopEnd),
                    ) {
                        Icon(
                            imageVector = Icons.Default.Close,
                            contentDescription = "Close",
                            tint = Color.White,
                            modifier = Modifier.size(26.dp),
                        )
                    }

                    // Bottom control bar (play/pause + elapsed/total time)
                    Row(
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .fillMaxWidth()
                            .background(Color.Black.copy(alpha = 0.35f))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        IconButton(
                            onClick = viewModel::onPlayPause,
                            modifier = Modifier.size(36.dp),
                        ) {
                            Icon(
                                imageVector = if (uiState.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (uiState.isPlaying) "Pause" else "Play",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp),
                            )
                        }
                        Text(
                            text = "${formatDuration(position)} / ${formatDuration(uiState.duration)}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            maxLines = 1,
                        )
                    }
                }
            }

            // Thin progress line over the very bottom edge (only shown when HUD is hidden).
            if (!showControls) {
                LinearProgressIndicator(
                    progress = { progress },
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .height(3.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                    drawStopIndicator = {},
                    gapSize = 0.dp,
                )
            }
        }
    }
}

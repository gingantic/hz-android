package com.rhnxdev.hzplayer.presentation.player

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.animation.core.animate
import androidx.compose.foundation.gestures.draggable
import androidx.compose.foundation.gestures.rememberDraggableState
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.presentation.player.components.AudioPlayerSheet
import kotlin.math.roundToInt

@Composable
fun AudioPlayerScreen(
    viewModel: PlayerViewModel = hiltViewModel(),
    onBack: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val view = LocalView.current
    val density = LocalDensity.current
    var dragOffset by remember { mutableFloatStateOf(0f) }
    val dismissThreshold = with(density) { 150.dp.toPx() }

    // Edge-to-edge: draw behind system bars
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
        controller.show(WindowInsetsCompat.Type.systemBars())

        onDispose {
            window.navigationBarColor = originalNavColor
            window.statusBarColor = originalStatusColor
            WindowCompat.setDecorFitsSystemWindows(window, true)
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .offset { IntOffset(0, dragOffset.roundToInt()) }
            .background(MaterialTheme.colorScheme.surface)
            .draggable(
                state = rememberDraggableState { delta ->
                    dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                },
                orientation = Orientation.Vertical,
                onDragStopped = { velocity ->
                    // Velocity is in pixels per second. Positive velocity means swiping down.
                    val isFlick = velocity > 1000f
                    val screenHeight = view.height.toFloat()
                    if (dragOffset > dismissThreshold || isFlick) {
                        // Animate down to the bottom of the screen with the release velocity
                        animate(
                            initialValue = dragOffset,
                            targetValue = screenHeight,
                            initialVelocity = velocity,
                        ) { value, _ ->
                            dragOffset = value
                        }
                        onBack()
                    } else {
                        animate(
                            initialValue = dragOffset,
                            targetValue = 0f
                        ) { value, _ ->
                            dragOffset = value
                        }
                    }
                }
            ),
    ) {
        // Drag handle at top (shifted down to prevent gesture conflict with notification drawer)
        Box(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 24.dp)
                .width(40.dp)
                .height(4.dp)
                .background(
                    Color.White.copy(alpha = 0.3f),
                    CircleShape,
                ),
        )

        AudioPlayerSheet(
            uiState = uiState,
            title = uiState.currentTitle,
            artist = uiState.currentArtist,
            onPlayPause = viewModel::onPlayPause,
            onSkipNext = viewModel::onSkipNext,
            onSkipPrevious = viewModel::onSkipPrevious,
            onSeekTo = viewModel::onSeekTo,
            onToggleShuffle = viewModel::onToggleShuffle,
            onCycleRepeat = viewModel::onCycleRepeatMode,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding()
                .padding(top = 40.dp),
        )

        // Close (back) button (shifted down to prevent gesture conflict)
        IconButton(
            onClick = onBack,
            modifier = Modifier
                .align(Alignment.TopStart)
                .statusBarsPadding()
                .padding(top = 16.dp, start = 16.dp)
                .size(40.dp),
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                contentDescription = "Back",
                tint = Color.White,
            )
        }
    }
}

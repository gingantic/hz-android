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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.R
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
import androidx.compose.ui.platform.LocalConfiguration
import android.content.res.Configuration
import androidx.compose.ui.graphics.luminance
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.presentation.player.components.AudioPlayerSheet
import com.rhnxdev.hzplayer.presentation.player.components.AudioQueueSheet
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

    val surfaceColor = MaterialTheme.colorScheme.surface
    val isLight = surfaceColor.luminance() > 0.5f
    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_START || event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                viewModel.onAppForeground()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Edge-to-edge: draw behind system bars
    DisposableEffect(surfaceColor, isLight) {
        val window = (view.context as? android.app.Activity)?.window
        if (window == null) return@DisposableEffect onDispose {}

        val originalNavColor = window.navigationBarColor
        val originalStatusColor = window.statusBarColor

        WindowCompat.setDecorFitsSystemWindows(window, false)
        window.navigationBarColor = Color.Transparent.toArgb()
        window.statusBarColor = Color.Transparent.toArgb()

        val controller = WindowInsetsControllerCompat(window, view)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.show(WindowInsetsCompat.Type.systemBars())
        controller.isAppearanceLightStatusBars = isLight
        controller.isAppearanceLightNavigationBars = isLight

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
            .run {
                if (isLandscape) this else draggable(
                    state = rememberDraggableState { delta ->
                        dragOffset = (dragOffset + delta).coerceAtLeast(0f)
                    },
                    orientation = Orientation.Vertical,
                    onDragStopped = { velocity ->
                        val isFlick = velocity > 1000f
                        val screenHeight = view.height.toFloat()
                        if (dragOffset > dismissThreshold || isFlick) {
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
                )
            },
    ) {
        // Drag handle at top (shifted down to prevent gesture conflict with notification drawer) - hide in landscape
        if (!isLandscape) {
            Box(
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = 24.dp)
                    .width(40.dp)
                    .height(4.dp)
                    .background(
                        MaterialTheme.colorScheme.onSurface.copy(alpha = 0.2f),
                        CircleShape,
                    ),
            )
        }

        AudioPlayerSheet(
            uiState = uiState,
            positionFlow = viewModel.position,
            title = uiState.currentTitle,
            artist = uiState.currentArtist,
            onPlayPause = viewModel::onPlayPause,
            onSkipNext = viewModel::onSkipNext,
            onSkipPrevious = viewModel::onSkipPrevious,
            onSeekTo = viewModel::onSeekTo,
            onToggleShuffle = viewModel::onToggleShuffle,
            onCycleRepeat = viewModel::onCycleRepeatMode,
            onToggleQueue = viewModel::onToggleAudioQueue,
            modifier = Modifier
                .fillMaxSize()
                .background(MaterialTheme.colorScheme.surface)
                .systemBarsPadding()
                .padding(
                    top = if (isLandscape) Spacing.md else 40.dp,
                    bottom = if (isLandscape) Spacing.md else Spacing.xl,
                    start = if (isLandscape) 72.dp else Spacing.xl,
                    end = if (isLandscape) Spacing.lg else Spacing.xl,
                ),
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
                contentDescription = stringResource(R.string.back_cd),
                tint = MaterialTheme.colorScheme.onSurface,
            )
        }

        // Audio queue bottom sheet
        if (uiState.showAudioQueue) {
            AudioQueueSheet(
                queue = uiState.audioQueue,
                currentIndex = uiState.audioQueueIndex,
                onTrackSelected = viewModel::onAudioQueueSelect,
                onDismiss = viewModel::onToggleAudioQueue,
            )
        }
    }
}

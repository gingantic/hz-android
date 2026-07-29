package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.StateFlow

/**
 * Draws the seek / slide / hold-to-speed gesture cues and owns their auto-hide
 * timers. Takes the stable [PlayerGestureState] object and reads its mutable
 * fields *inside* this composable, so per-pointer-move updates (seek delta,
 * slide value, hold flag) only recompose this leaf — not the whole player
 * screen, which never subscribes to those fields anymore.
 */
@Composable
fun GestureCueIndicators(
    state: PlayerGestureState,
    positionFlow: StateFlow<Long>,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    val position by positionFlow.collectAsStateWithLifecycle()

    // Auto-hide the seek cue after the last tick (double-tap re-arms via the tick).
    // Also keyed on isDragSeeking so the timer re-arms when a drag ends — keyed on
    // the tick alone it would never re-run after the last drag tick and the cue
    // could stay stuck on screen.
    LaunchedEffect(state.seekShowTick, state.isDragSeeking) {
        if (state.seekShowTick > 0 && !state.isDragSeeking && state.seekVisible) {
            delay(1200)
            state.seekVisible = false
            state.seekDelta = 0L
        }
    }

    // Auto-hide the slide (brightness / volume) cue.
    LaunchedEffect(state.slideShowCount) {
        if (state.slideVisible) {
            delay(1000)
            state.slideVisible = false
        }
    }

    // Real video time skipped during the hold: snapshot the player position at
    // hold start and diff against the live position — no synthetic counters, this
    // is exactly how far the video actually advanced.
    var holdStartPositionMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isHoldSpeeding) {
        if (state.isHoldSpeeding) holdStartPositionMs = positionFlow.value
    }
    val holdSkippedMs = if (state.isHoldSpeeding) {
        (position - holdStartPositionMs).coerceAtLeast(0L)
    } else 0L

    // Hold-to-speed visual cue — top-center pill: ▶▶ Nx · video time skipped.
    AnimatedVisibility(
        visible = state.isHoldSpeeding,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .systemBarsPadding(),
            contentAlignment = Alignment.TopCenter,
        ) {
            Row(
                modifier = Modifier
                    .padding(top = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 16.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                Icon(
                    imageVector = Icons.Default.FastForward,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(18.dp),
                )
                Text(
                    text = "${"%.1f".format(state.holdSpeed)}x",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
                Text(
                    text = "+${formatDuration(holdSkippedMs)}",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Medium,
                    color = Color.White.copy(alpha = 0.8f),
                )
            }
        }
    }

    SeekIndicators(
        state = state,
        positionFlow = positionFlow,
        duration = duration,
        modifier = modifier.fillMaxSize(),
    )

    SlideIndicator(
        state = state,
        modifier = Modifier.fillMaxSize(),
    )
}

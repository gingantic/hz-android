package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
    LaunchedEffect(state.seekShowTick) {
        if (state.seekShowTick > 0 && !state.isDragSeeking) {
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

    // Accumulate real lead time while hold-to-speed is engaged. At Nx the video
    // advances (N-1) extra seconds per real second, so a 250ms tick gains
    // 250ms * (holdSpeed - 1). Reads state.holdSpeed live (ramps 2x→4x).
    var holdGainedMs by remember { mutableLongStateOf(0L) }
    LaunchedEffect(state.isHoldSpeeding) {
        if (!state.isHoldSpeeding) {
            holdGainedMs = 0L
            return@LaunchedEffect
        }
        while (state.isHoldSpeeding) {
            delay(250)
            holdGainedMs += (250 * (state.holdSpeed - 1f)).toLong()
        }
    }

    // Hold-to-speed visual cue (above HUD, below slide/seek indicators)
    AnimatedVisibility(
        visible = state.isHoldSpeeding,
        modifier = Modifier.fillMaxSize(),
        enter = fadeIn(animationSpec = tween(120)),
        exit = fadeOut(animationSpec = tween(200)),
    ) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Row(
                modifier = Modifier
                    .padding(end = 24.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.6f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    text = "Hold · ${"%.1f".format(state.holdSpeed)}x  +${formatDuration(holdGainedMs)}",
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
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

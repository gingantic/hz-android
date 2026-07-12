package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureState
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SeekIndicators(
    state: PlayerGestureState,
    positionFlow: StateFlow<Long>,
    duration: Long,
    modifier: Modifier = Modifier,
) {
    val currentPosition by positionFlow.collectAsStateWithLifecycle()
    if (state.isDragSeeking) {
        DragSeekIndicator(
            deltaMs = state.seekDelta,
            currentPositionMs = currentPosition,
            durationMs = duration,
            visible = state.seekVisible,
            modifier = modifier.fillMaxSize(),
            isForward = state.isSeekForward,
        )
    } else {
        SeekIndicator(
            deltaMs = state.seekDelta,
            currentPositionMs = currentPosition,
            visible = state.seekVisible,
            modifier = modifier.fillMaxSize(),
            isForward = state.isSeekForward,
        )
    }
}

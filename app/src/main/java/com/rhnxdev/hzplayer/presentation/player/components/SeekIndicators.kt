package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.foundation.layout.fillMaxSize
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.flow.StateFlow

@Composable
fun SeekIndicators(
    positionFlow: StateFlow<Long>,
    duration: Long,
    isDragSeeking: Boolean,
    seekDelta: Long,
    seekVisible: Boolean,
    isSeekForward: Boolean,
) {
    val currentPosition by positionFlow.collectAsStateWithLifecycle()
    if (isDragSeeking) {
        DragSeekIndicator(
            deltaMs = seekDelta,
            currentPositionMs = currentPosition,
            durationMs = duration,
            visible = seekVisible,
            modifier = Modifier.fillMaxSize(),
            isForward = isSeekForward,
        )
    } else {
        SeekIndicator(
            deltaMs = seekDelta,
            currentPositionMs = currentPosition,
            visible = seekVisible,
            modifier = Modifier.fillMaxSize(),
            isForward = isSeekForward,
        )
    }
}

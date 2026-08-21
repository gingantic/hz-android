package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FastForward
import androidx.compose.material.icons.filled.FastRewind
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.util.formatDuration

@Composable
fun DragSeekIndicator(
    deltaMs: Long,
    currentPositionMs: Long,
    durationMs: Long,
    visible: Boolean,
    modifier: Modifier = Modifier,
    isForward: Boolean = deltaMs >= 0,
) {
    var lastDisplayedDeltaMs by remember { mutableLongStateOf(deltaMs) }
    var lastTargetMs by remember { mutableLongStateOf((currentPositionMs + deltaMs).coerceIn(0L, durationMs)) }
    var lastIsForward by remember { mutableStateOf(isForward) }

    if (deltaMs != 0L) {
        lastDisplayedDeltaMs = deltaMs
        lastTargetMs = (currentPositionMs + deltaMs).coerceIn(0L, durationMs)
        lastIsForward = isForward
    }

    val displayDelta = if (deltaMs != 0L) deltaMs else lastDisplayedDeltaMs
    val targetMs = if (deltaMs != 0L) (currentPositionMs + deltaMs).coerceIn(0L, durationMs) else lastTargetMs
    val displayIsForward = if (deltaMs != 0L) isForward else lastIsForward

    val fraction = if (durationMs > 0) (targetMs.toFloat() / durationMs.toFloat()).coerceIn(0f, 1f) else 0f

    val deltaSeconds = (displayDelta / 1000).let {
        if (it == 0L) "" else if (it > 0) "+${it}s" else "${it}s"
    }

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(300)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                Icon(
                    imageVector = if (isForward) Icons.Default.FastForward else Icons.Default.FastRewind,
                    contentDescription = null,
                    tint = Color.White,
                    modifier = Modifier.size(24.dp),
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                    ) {
                        Text(
                            text = "${formatDuration(targetMs)} / ${formatDuration(durationMs)}",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = Color.White,
                        )
                        if (deltaSeconds.isNotEmpty()) {
                            Text(
                                text = "[$deltaSeconds]",
                                fontSize = 14.sp,
                                fontWeight = FontWeight.Medium,
                                color = if (isForward) Color(0xFF4CAF50) else Color(0xFFE57373),
                            )
                        }
                    }

                    LinearProgressIndicator(
                        progress = { fraction },
                        modifier = Modifier.width(160.dp),
                        color = Color.White,
                        trackColor = Color.White.copy(alpha = 0.25f),
                        strokeCap = StrokeCap.Round,
                        drawStopIndicator = {},
                        gapSize = 0.dp,
                    )
                }
            }
        }
    }
}

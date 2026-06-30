package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.core.util.formatDuration

/**
 * Seek indicator overlay shown during double-tap or drag seek.
 *
 * Displays the seek delta (e.g. "+10s" or "-30s") with a forward/backward
 * arrow, and the resulting target timestamp. Positioned on the left side for
 * rewind and the right side for fast-forward, mirroring VLC's UX.
 *
 * @param deltaMs — seek amount in milliseconds (positive = forward, negative = backward)
 * @param currentPositionMs — current playback position *before* the seek is applied
 * @param visible — whether the indicator is shown
 * @param modifier — optional modifier
 */
@Composable
fun SeekIndicator(
    deltaMs: Long,
    currentPositionMs: Long,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    val alpha by animateFloatAsState(
        targetValue = if (visible) 1f else 0f,
        animationSpec = tween(durationMillis = if (visible) 150 else 400),
        label = "seekAlpha",
    )

    val isForward = deltaMs >= 0
    val targetMs = (currentPositionMs + deltaMs).coerceAtLeast(0)

    // Format the delta as "+10s" or "-30s"
    val deltaSeconds = (deltaMs / 1000).let {
        if (it == 0L) "" else if (it > 0) "+${it}s" else "${it}s"
    }
    val targetTime = formatDuration(targetMs)

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = if (isForward) Alignment.CenterEnd else Alignment.CenterStart,
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(animationSpec = tween(150)),
            exit = fadeOut(animationSpec = tween(400)),
        ) {
            Column(
                modifier = Modifier
                    .padding(horizontal = 48.dp)
                    .alpha(alpha)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color.Black.copy(alpha = 0.65f))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    Icon(
                        imageVector = if (isForward) Icons.AutoMirrored.Filled.ArrowForward
                        else Icons.AutoMirrored.Filled.ArrowBack,
                        contentDescription = null,
                        tint = Color.White,
                        modifier = Modifier.size(28.dp),
                    )
                    Text(
                        text = deltaSeconds,
                        fontSize = 28.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White,
                    )
                }
                Text(
                    text = targetTime,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Normal,
                    color = Color.White.copy(alpha = 0.75f),
                )
            }
        }
    }
}

package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.material3.MaterialTheme
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Linear progress bar in the app's house style — a chunky, fully-rounded track
 * matching [HzPlayerSlider]: [accent] fill over an [onSurface]-tinted rest.
 *
 * Pass a [progress] in 0f..1f for a determinate bar; pass null for an
 * indeterminate sweep (a rounded segment that slides across the track).
 *
 * @param progress fraction 0f..1f, or null for indeterminate
 * @param accent fill colour for the filled portion / moving segment
 * @param trackColor colour of the unfilled track
 * @param trackHeight bar thickness
 */
@Composable
fun HzPlayerProgressBar(
    progress: Float?,
    modifier: Modifier = Modifier,
    accent: Color = MaterialTheme.colorScheme.primary,
    trackColor: Color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
    trackHeight: Dp = 6.dp,
) {
    if (progress == null) {
        IndeterminateBar(modifier, accent, trackColor, trackHeight)
    } else {
        val animated by animateFloatAsState(
            targetValue = progress.coerceIn(0f, 1f),
            label = "hzProgress",
        )
        Canvas(
            modifier = modifier
                .fillMaxWidth()
                .height(trackHeight),
        ) {
            val radius = CornerRadius(size.height / 2f, size.height / 2f)
            // Rest track.
            drawRoundRect(color = trackColor, cornerRadius = radius)
            // Filled portion.
            val fillWidth = size.width * animated
            if (fillWidth > 0f) {
                drawRoundRect(
                    color = accent,
                    size = Size(fillWidth.coerceAtLeast(size.height), size.height),
                    cornerRadius = radius,
                )
            }
        }
    }
}

@Composable
private fun IndeterminateBar(
    modifier: Modifier,
    accent: Color,
    trackColor: Color,
    trackHeight: Dp,
) {
    val transition = rememberInfiniteTransition(label = "hzProgressIndeterminate")
    // Head sweeps left→right; the visible segment is [start, head].
    val head by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1100, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "hzProgressHead",
    )
    val segment = 0.35f
    Canvas(
        modifier = modifier
            .fillMaxWidth()
            .height(trackHeight),
    ) {
        val radius = CornerRadius(size.height / 2f, size.height / 2f)
        drawRoundRect(color = trackColor, cornerRadius = radius)
        val end = head * (1f + segment)
        val start = (end - segment).coerceAtLeast(0f)
        val left = start * size.width
        val right = (end.coerceAtMost(1f)) * size.width
        val width = (right - left)
        if (width > 0f) {
            drawRoundRect(
                color = accent,
                topLeft = Offset(left, 0f),
                size = Size(width.coerceAtLeast(size.height), size.height),
                cornerRadius = radius,
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun HzPlayerProgressBarPreview() {
    HzPlayerTheme {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HzPlayerProgressBar(progress = 0f)
            HzPlayerProgressBar(progress = 0.35f)
            HzPlayerProgressBar(progress = 0.8f)
            HzPlayerProgressBar(progress = 1f)
            HzPlayerProgressBar(progress = null)
        }
    }
}

package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

private val TrackHeight = 3.dp
private val TrackHeightActive = 5.dp
private val TouchTargetHeight = 32.dp

@Composable
fun PlayerSeekBar(
    currentPosition: Long,
    duration: Long,
    bufferedPercentage: Int,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
    onScrub: ((Long) -> Unit)? = null,
    abLoopStartMs: Long? = null,
    abLoopEndMs: Long? = null,
) {
    // Bug 5 fix: when duration <= 0 (live stream or unknown), show position at 0
    // and disable seek gestures entirely. Without this guard, tap/drag computes
    // (fraction * 0) = 0 which always seeks to the start.
    val canSeek = duration > 0

    val fraction = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f
    val bufferedFraction = (bufferedPercentage / 100f).coerceIn(0f, 1f)

    var isDragging by remember { mutableStateOf(false) }
    var dragFraction by remember { mutableFloatStateOf(fraction) }
    val displayFraction = if (isDragging) dragFraction else fraction

    val trackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.15f)
    val bufferedColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
    val activeColor = MaterialTheme.colorScheme.primary
    val abMarkerColor = Color(0xFF4CAF50)

    // A-B loop fractions along the track (null until each point is marked).
    val abStartFraction = abLoopStartMs?.let { if (duration > 0) (it.toFloat() / duration).coerceIn(0f, 1f) else null }
    val abEndFraction = abLoopEndMs?.let { if (duration > 0) (it.toFloat() / duration).coerceIn(0f, 1f) else null }

    Row(
        modifier = modifier.fillMaxWidth().padding(horizontal = Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = if (isDragging) formatDuration((dragFraction * duration).toLong()) else formatDuration(currentPosition),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Box(
            modifier = Modifier
                .weight(1f)
                .height(TouchTargetHeight)
                .padding(horizontal = Spacing.sm)
                .pointerInput(duration, canSeek) {
                    if (!canSeek) return@pointerInput
                    detectTapGestures { offset ->
                        val f = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                        onSeek((f * duration).toLong())
                    }
                }
                .pointerInput(duration, canSeek) {
                    if (!canSeek) return@pointerInput
                    detectHorizontalDragGestures(
                        onDragStart = { offset ->
                            isDragging = true
                            dragFraction = (offset.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onSeekStart()
                            onScrub?.invoke((dragFraction * duration).toLong())
                        },
                        onHorizontalDrag = { change, _ ->
                            change.consume()
                            dragFraction = (change.position.x / size.width.toFloat()).coerceIn(0f, 1f)
                            onScrub?.invoke((dragFraction * duration).toLong())
                        },
                        onDragEnd = {
                            onSeek((dragFraction * duration).toLong())
                            isDragging = false
                            onSeekEnd()
                        },
                        onDragCancel = {
                            isDragging = false
                            onSeekEnd()
                        },
                    )
                },
            contentAlignment = Alignment.Center,
        ) {
            val currentTrackHeight = if (isDragging) TrackHeightActive else TrackHeight

            Canvas(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(currentTrackHeight),
            ) {
                val trackH = size.height
                val cornerR = CornerRadius(trackH / 2f)

                // Background track
                drawRoundRect(
                    color = trackColor,
                    size = Size(size.width, trackH),
                    cornerRadius = cornerR,
                )

                // Buffered track
                if (bufferedFraction > 0f) {
                    drawRoundRect(
                        color = bufferedColor,
                        size = Size(size.width * bufferedFraction, trackH),
                        cornerRadius = cornerR,
                    )
                }

                // Active (played) track
                if (displayFraction > 0f) {
                    drawRoundRect(
                        color = activeColor,
                        size = Size(size.width * displayFraction, trackH),
                        cornerRadius = cornerR,
                    )
                }

                // A-B loop: shade the region and draw a tick at each marked point.
                val markerH = trackH * 2.4f
                if (abStartFraction != null && abEndFraction != null) {
                    val xa = size.width * abStartFraction
                    val xb = size.width * abEndFraction
                    drawRect(
                        color = abMarkerColor.copy(alpha = 0.25f),
                        topLeft = Offset(xa, trackH / 2f - markerH / 2f),
                        size = Size((xb - xa).coerceAtLeast(0f), markerH),
                    )
                }
                listOfNotNull(abStartFraction, abEndFraction).forEach { f ->
                    val x = (size.width * f).coerceIn(0f, size.width)
                    drawRoundRect(
                        color = abMarkerColor,
                        topLeft = Offset(x - 1.5f.dp.toPx(), trackH / 2f - markerH / 2f),
                        size = Size(3f.dp.toPx(), markerH),
                        cornerRadius = CornerRadius(1.5f.dp.toPx()),
                    )
                }
            }
        }

        Text(
            text = formatDuration(duration),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun PlayerSeekBarPreview() {
    HzPlayerTheme {
        PlayerSeekBar(
            currentPosition = 123000,
            duration = 369000,
            bufferedPercentage = 60,
            onSeek = {},
            onSeekStart = {},
            onSeekEnd = {},
            modifier = Modifier.padding(Spacing.sm),
        )
    }
}

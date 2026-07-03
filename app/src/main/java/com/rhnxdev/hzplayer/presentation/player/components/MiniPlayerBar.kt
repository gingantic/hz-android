package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Text
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntOffset
import kotlin.math.roundToInt
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.components.MediaType
import com.rhnxdev.hzplayer.core.components.ThumbnailPlaceholder
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun MiniPlayerBar(
    title: String,
    subtitle: String,
    isPlaying: Boolean,
    progress: Float,
    onPlayPause: () -> Unit,
    onNext: () -> Unit,
    onClick: () -> Unit,
    onDismiss: () -> Unit,
    visible: Boolean,
    modifier: Modifier = Modifier,
) {
    var isSwiped by remember { mutableStateOf(false) }

    LaunchedEffect(visible) {
        if (visible) {
            isSwiped = false
        }
    }

    val containerShape: Shape = RoundedCornerShape(topStart = 12.dp, topEnd = 12.dp)

    AnimatedVisibility(
        visible = visible,
        enter = slideInVertically { it },
        exit = slideOutVertically(animationSpec = tween(durationMillis = 300)) { it },
    ) {
        val dismissState = rememberSwipeToDismissBoxState(
            confirmValueChange = { value ->
                value != SwipeToDismissBoxValue.Settled
            },
            positionalThreshold = { distance -> distance * 0.15f },
        )

        // Trigger dismissal only after the horizontal swipe animation is completed
        LaunchedEffect(dismissState.currentValue) {
            if (dismissState.currentValue == SwipeToDismissBoxValue.StartToEnd ||
                dismissState.currentValue == SwipeToDismissBoxValue.EndToStart
            ) {
                if (visible) {
                    isSwiped = true
                    onDismiss()
                }
            }
        }

        var cardWidth by remember { mutableIntStateOf(0) }

        SwipeToDismissBox(
            state = dismissState,
            modifier = Modifier.clip(containerShape),
            backgroundContent = {
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.errorContainer),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    // Left X icon (visible when swiping from start to end)
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(start = Spacing.xl)
                            .graphicsLayer {
                                alpha = if (dismissState.dismissDirection == SwipeToDismissBoxValue.StartToEnd) 1f else 0f
                            },
                    )

                    Spacer(modifier = Modifier.weight(1f))

                    // Right X icon (visible when swiping from end to start)
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Close",
                        tint = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier
                            .padding(end = Spacing.xl)
                            .graphicsLayer {
                                alpha = if (dismissState.dismissDirection == SwipeToDismissBoxValue.EndToStart) 1f else 0f
                            },
                    )
                }
            },
            enableDismissFromStartToEnd = true,
            enableDismissFromEndToStart = true,
        ) {
            Box(
                modifier = modifier
                    .fillMaxWidth()
                    .onSizeChanged { cardWidth = it.width }
                    .offset {
                        val rawOffset = try { dismissState.requireOffset() } catch (e: Exception) { 0f }
                        val maxSwipe = cardWidth * 0.25f
                        val clampedOffset = rawOffset.coerceIn(-maxSwipe, maxSwipe)
                        IntOffset(x = (clampedOffset - rawOffset).roundToInt(), y = 0)
                    }
                    .clip(containerShape)
            ) {
                // Card-like content area
                Surface(
                    modifier = Modifier.fillMaxWidth(),
                    shape = containerShape,
                    color = MaterialTheme.colorScheme.surfaceContainerLow,
                    tonalElevation = 3.dp,
                    shadowElevation = 8.dp,
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Clicking metadata navigates to full player
                        Row(
                            modifier = Modifier
                                .weight(1f)
                                .clickable(onClick = onClick),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            // Album thumbnail
                            Box(
                                modifier = Modifier
                                    .size(44.dp)
                                    .clip(RoundedCornerShape(Spacing.sm)),
                            ) {
                                ThumbnailPlaceholder(mediaType = MediaType.AUDIO)
                            }

                            Spacer(modifier = Modifier.width(Spacing.sm))

                            // Track info
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = title,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurface,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                                Text(
                                    text = subtitle,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                        }

                        // Controls
                        IconButton(onClick = onPlayPause) {
                            Icon(
                                imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (isPlaying) "Pause" else "Play",
                                tint = MaterialTheme.colorScheme.primary,
                            )
                        }
                        IconButton(onClick = onNext) {
                            Icon(
                                imageVector = Icons.Default.SkipNext,
                                contentDescription = "Next",
                                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }

                // Progress bar at top (thin line, full width, overlaying the card's top edge)
                LinearProgressIndicator(
                    progress = { progress.coerceIn(0f, 1f) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.dp)
                        .align(Alignment.TopCenter),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun MiniPlayerBarPreview() {
    HzPlayerTheme {
        MiniPlayerBar(
            title = "Get Lucky",
            subtitle = "Daft Punk",
            isPlaying = true,
            progress = 0.45f,
            onPlayPause = {},
            onNext = {},
            onClick = {},
            onDismiss = {},
            visible = true,
        )
    }
}

package com.rhnxdev.hzplayer.core.components

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

enum class ShimmerShape {
    /** 16:9 cards in a 2-column grid — for video grid view */
    VIDEO_CARD,
    /** 1:1 square + title/artist/tracks — for album grid */
    ALBUM_CARD,
    /** 56x40 thumbnail + 2 text lines — for MediaListItem (video list, songs, artists, search) */
    LIST_ITEM,
    /** Category title + horizontal row of 16:9 cards — for video category layout */
    VIDEO_CATEGORY,
    /** 40dp square + 3 text lines — for storage root cards */
    STORAGE_ROOT,
    /** 28dp square + 2 text lines — for FileListItem (directory contents) */
    FILE_LIST_ITEM,
}

@Composable
fun MediaLoadingState(
    itemCount: Int = 6,
    shape: ShimmerShape = ShimmerShape.VIDEO_CATEGORY,
    modifier: Modifier = Modifier,
) {
    val shimmerColors = listOf(
        Color.LightGray.copy(alpha = 0.3f),
        Color.LightGray.copy(alpha = 0.6f),
        Color.LightGray.copy(alpha = 0.3f),
    )

    val transition = rememberInfiniteTransition(label = "shimmer")
    val translateAnim by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1000f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = 1200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart,
        ),
        label = "shimmer_translate",
    )

    val brush = remember(translateAnim) {
        Brush.linearGradient(
            colors = shimmerColors,
            start = Offset.Zero,
            end = Offset(x = translateAnim, y = translateAnim),
        )
    }

    Column(
        modifier = modifier.padding(Spacing.sm),
        verticalArrangement = Arrangement.spacedBy(Spacing.sm),
    ) {
        when (shape) {
            ShimmerShape.VIDEO_CARD -> {
                repeat(itemCount / 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        repeat(2) {
                            ShimmerCard(
                                brush = brush,
                                aspectRatio = 16f / 9f,
                                modifier = Modifier.weight(1f),
                            )
                        }
                    }
                }
            }

            ShimmerShape.ALBUM_CARD -> {
                repeat(itemCount / 2) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                    ) {
                        repeat(2) {
                            Column(modifier = Modifier.weight(1f)) {
                                // Square album art shimmer
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .aspectRatio(1f)
                                        .clip(RoundedCornerShape(Spacing.sm))
                                        .background(brush),
                                )
                                Spacer(modifier = Modifier.height(Spacing.sm))
                                // Title line
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.8f)
                                        .height(12.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
                                )
                                Spacer(modifier = Modifier.height(4.dp))
                                // Artist line
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.5f)
                                        .height(10.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
                                )
                                Spacer(modifier = Modifier.height(2.dp))
                                // Track count line
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth(0.3f)
                                        .height(8.dp)
                                        .clip(RoundedCornerShape(4.dp))
                                        .background(brush),
                                )
                            }
                        }
                    }
                }
            }

            ShimmerShape.LIST_ITEM -> {
                repeat(itemCount) {
                    ShimmerRow(brush = brush)
                }
            }

            ShimmerShape.VIDEO_CATEGORY -> {
                repeat(itemCount) {
                    Column(modifier = Modifier.fillMaxWidth()) {
                        // Category title line
                        Box(
                            modifier = Modifier
                                .padding(horizontal = Spacing.lg)
                                .fillMaxWidth(0.3f)
                                .height(16.dp)
                                .clip(RoundedCornerShape(4.dp))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.height(Spacing.sm))
                        // Horizontal row of 16:9 cards
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(horizontal = Spacing.lg),
                            horizontalArrangement = Arrangement.spacedBy(Spacing.sm),
                        ) {
                            repeat(3) {
                                ShimmerCard(
                                    brush = brush,
                                    aspectRatio = 16f / 9f,
                                )
                            }
                        }
                    }
                }
            }

            ShimmerShape.STORAGE_ROOT -> {
                repeat(itemCount) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            .clip(RoundedCornerShape(Spacing.sm))
                            .background(Color.LightGray.copy(alpha = 0.08f))
                            .padding(Spacing.lg),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 40dp icon placeholder
                        Box(
                            modifier = Modifier
                                .size(40.dp)
                                .clip(RoundedCornerShape(Spacing.sm))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            // Name line
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.6f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // Path line
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush),
                            )
                            Spacer(modifier = Modifier.height(4.dp))
                            // Item count line
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.3f)
                                    .height(8.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush),
                            )
                        }
                    }
                }
            }

            ShimmerShape.FILE_LIST_ITEM -> {
                repeat(itemCount) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
                            .clip(RoundedCornerShape(Spacing.sm))
                            .background(Color.LightGray.copy(alpha = 0.08f))
                            .padding(Spacing.sm),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // 28dp icon placeholder
                        Box(
                            modifier = Modifier
                                .size(28.dp)
                                .clip(RoundedCornerShape(Spacing.xs))
                                .background(brush),
                        )
                        Spacer(modifier = Modifier.width(Spacing.md))
                        Column(modifier = Modifier.weight(1f)) {
                            // Name line
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.5f)
                                    .height(14.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush),
                            )
                            Spacer(modifier = Modifier.height(6.dp))
                            // Subtitle line
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth(0.4f)
                                    .height(10.dp)
                                    .clip(RoundedCornerShape(4.dp))
                                    .background(brush),
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ShimmerCard(
    brush: Brush,
    aspectRatio: Float,
    modifier: Modifier = Modifier,
) {
    Column(modifier = modifier) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(aspectRatio)
                .clip(RoundedCornerShape(Spacing.sm))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(Spacing.sm))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.8f)
                .height(12.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
        Spacer(modifier = Modifier.height(4.dp))
        Box(
            modifier = Modifier
                .fillMaxWidth(0.5f)
                .height(10.dp)
                .clip(RoundedCornerShape(4.dp))
                .background(brush),
        )
    }
}

@Composable
private fun ShimmerRow(brush: Brush) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = Spacing.sm, vertical = Spacing.xs)
            .clip(RoundedCornerShape(Spacing.sm))
            .background(Color.LightGray.copy(alpha = 0.08f))
            .padding(Spacing.sm),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            modifier = Modifier
                .size(width = 56.dp, height = 40.dp)
                .clip(RoundedCornerShape(Spacing.xs))
                .background(brush),
        )
        Spacer(modifier = Modifier.width(Spacing.md))
        Column(modifier = Modifier.weight(1f)) {
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.6f)
                    .height(12.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Box(
                modifier = Modifier
                    .fillMaxWidth(0.3f)
                    .height(10.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(brush),
            )
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun MediaLoadingStatePreview() {
    HzPlayerTheme {
        MediaLoadingState(itemCount = 6, shape = ShimmerShape.VIDEO_CATEGORY)
    }
}

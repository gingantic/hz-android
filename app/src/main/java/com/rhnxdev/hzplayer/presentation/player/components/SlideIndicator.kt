package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.presentation.player.components.PlayerGestureState

enum class SlideType { BRIGHTNESS, VOLUME }

@Composable
fun SlideIndicator(
    state: PlayerGestureState,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center,
    ) {
        AnimatedVisibility(
            visible = state.slideVisible,
            enter = fadeIn(animationSpec = tween(120)),
            exit = fadeOut(animationSpec = tween(300)),
        ) {
            Row(
                modifier = Modifier
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color.Black.copy(alpha = 0.55f))
                    .padding(horizontal = 18.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                when (state.slideType) {
                    SlideType.BRIGHTNESS -> BrightnessIcon(
                        modifier = Modifier.size(22.dp),
                        tint = Color.White,
                    )
                    SlideType.VOLUME -> {
                        Icon(
                            imageVector = Icons.AutoMirrored.Filled.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp),
                        )
                    }
                }

                LinearProgressIndicator(
                    progress = { state.slideValue.coerceIn(0f, 1f) },
                    modifier = Modifier.width(120.dp),
                    color = Color.White,
                    trackColor = Color.White.copy(alpha = 0.25f),
                    strokeCap = StrokeCap.Round,
                )

                Spacer(modifier = Modifier.width(4.dp))

                Text(
                    text = "${(state.slideValue * 100).toInt()}%",
                    fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = Color.White,
                )
            }
        }
    }
}

/**
 * Simple sun icon drawn with Canvas — circle + 4 rays.
 * Avoids pulling in material-icons-extended just for BrightnessHigh.
 */
@Composable
private fun BrightnessIcon(
    modifier: Modifier = Modifier,
    tint: Color = Color.White,
) {
    Canvas(modifier = modifier) {
        val c = center
        val r = size.minDimension * 0.25f
        val rayLen = size.minDimension * 0.20f
        val rayW = 2.dp.toPx()

        // Circle
        drawCircle(color = tint, radius = r)

        // Rays (4 cardinal directions)
        for (angle in listOf(0f, 90f, 180f, 270f)) {
            val radians = Math.toRadians(angle.toDouble()).toFloat()
            val sx = c.x + kotlin.math.cos(radians) * (r + 2.dp.toPx())
            val sy = c.y + kotlin.math.sin(radians) * (r + 2.dp.toPx())
            val ex = c.x + kotlin.math.cos(radians) * (r + rayLen)
            val ey = c.y + kotlin.math.sin(radians) * (r + rayLen)
            drawLine(
                color = tint,
                start = androidx.compose.ui.geometry.Offset(sx, sy),
                end = androidx.compose.ui.geometry.Offset(ex, ey),
                strokeWidth = rayW,
                cap = StrokeCap.Round,
            )
        }
    }
}

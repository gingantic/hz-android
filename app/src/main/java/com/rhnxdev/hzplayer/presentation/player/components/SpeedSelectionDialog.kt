package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.R
import kotlin.math.roundToInt

private val SPEED_PRESETS = listOf(0.25f, 0.5f, 0.75f, 1.0f, 1.25f, 1.5f, 1.75f, 2.0f)
private const val LAST_INDEX = 7 // SPEED_PRESETS.size - 1

/** Snap [index] to the nearest valid preset index. */
private fun Float.roundToPresetIndex(): Float =
    roundToInt().coerceIn(0, LAST_INDEX).toFloat()

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SpeedSelectionDialog(
    currentSpeed: Float,
    onSpeedSelected: (Float) -> Unit,
    onDismiss: () -> Unit,
) {
    val initialIndex = (SPEED_PRESETS.indexOf(currentSpeed).takeIf { it >= 0 } ?: 3).toFloat()
    var sliderIndex by remember { mutableFloatStateOf(initialIndex) }

    SheetScaffold(
        title = stringResource(R.string.playback_speed),
        icon = Icons.Default.Speed,
        onDismiss = onDismiss,
        columnModifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 24.dp)
            .padding(bottom = 24.dp),
    ) {
        Spacer(modifier = Modifier.height(16.dp))

        val displaySpeed = SPEED_PRESETS[sliderIndex.roundToInt()]
        Text(
            text = stringResource(R.string.speed_value, displaySpeed),
            style = MaterialTheme.typography.headlineLarge.copy(
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
            ),
            modifier = Modifier.fillMaxWidth(),
            textAlign = TextAlign.Center,
        )

        Spacer(modifier = Modifier.height(16.dp))

        Slider(
            value = sliderIndex,
            onValueChange = { sliderIndex = it.roundToPresetIndex() },
            onValueChangeFinished = {
                onSpeedSelected(SPEED_PRESETS[sliderIndex.roundToInt()])
                onDismiss()
            },
            valueRange = 0f..LAST_INDEX.toFloat(),
            steps = LAST_INDEX - 1,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            colors = SliderDefaults.colors(
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
            ),
            thumb = {},
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    colors = SliderDefaults.colors(
                        activeTrackColor = MaterialTheme.colorScheme.primary,
                        inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                    ),
                    drawStopIndicator = null,
                    thumbTrackGapSize = 0.dp,
                )
            },
        )

        Spacer(modifier = Modifier.height(4.dp))

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            SPEED_PRESETS.forEach { speed ->
                Text(
                    text = stringResource(R.string.speed_value, speed),
                    fontSize = 10.sp,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.4f),
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))
    }
}


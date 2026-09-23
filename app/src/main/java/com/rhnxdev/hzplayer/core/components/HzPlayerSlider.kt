package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Slider in the app's house style: chunky gapless track filled with [accent],
 * solid round knob ringed in [thumbRingColor]. Step ticks and the stop
 * indicator are suppressed, so a discrete slider reads as a plain bar.
 *
 * Sizes are parameters, so the same look scales from a settings row down to the
 * equalizer's narrow vertical faders.
 *
 * @param accent — fill for the played track and the knob
 * @param thumbRingColor — colour behind the slider; rings the knob for a cut-out look
 * @param trackHeight — bar thickness (bar width when used vertically)
 * @param thumbSize — knob diameter
 * @param showThumb — hide the knob for a stepped picker that should read as a plain bar
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HzPlayerSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    valueRange: ClosedFloatingPointRange<Float> = 0f..1f,
    steps: Int = 0,
    enabled: Boolean = true,
    onValueChangeFinished: (() -> Unit)? = null,
    accent: Color = MaterialTheme.colorScheme.primary,
    thumbRingColor: Color = MaterialTheme.colorScheme.surface,
    trackHeight: Dp = 6.dp,
    thumbSize: Dp = 20.dp,
    showThumb: Boolean = true,
) {
    val fill = if (enabled) accent else accent.copy(alpha = 0.38f)

    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        steps = steps,
        enabled = enabled,
        modifier = modifier,
        thumb = {
            if (showThumb) {
                Box(
                    modifier = Modifier
                        .size(thumbSize)
                        .background(fill, CircleShape)
                        .border(thumbSize * 0.15f, thumbRingColor, CircleShape),
                )
            }
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                enabled = enabled,
                colors = SliderDefaults.colors(
                    activeTrackColor = fill,
                    inactiveTrackColor = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.12f),
                ),
                drawStopIndicator = null,
                drawTick = { _, _ -> },
                thumbTrackGapSize = 0.dp,
                modifier = Modifier.height(trackHeight),
            )
        },
    )
}

@PreviewLightDark
@Preview
@Composable
private fun HzPlayerSliderPreview() {
    HzPlayerTheme {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            HzPlayerSlider(value = 0.35f, onValueChange = {})
            HzPlayerSlider(value = 0.7f, onValueChange = {}, valueRange = 0f..10f, steps = 9)
            HzPlayerSlider(value = 0.5f, onValueChange = {}, enabled = false)
        }
    }
}

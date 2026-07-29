package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.GraphicEq
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.EqualizerBand
import com.rhnxdev.hzplayer.domain.model.EqualizerInfo
import kotlinx.coroutines.flow.StateFlow
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Equalizer bottom sheet: master switch, preset chips, a classic vertical
 * 10-band slider bank, plus bass boost and loudness enhancer. Band levels are
 * in millibels; sliders keep a local value while dragging and commit on
 * release so a drag doesn't flood the audio effect (and DataStore) with
 * intermediate levels.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EqualizerSheet(
    stateFlow: StateFlow<EqualizerInfo>,
    onEnabledChange: (Boolean) -> Unit,
    onBandChange: (Int, Int) -> Unit,
    onPresetSelect: (Int) -> Unit,
    onReset: () -> Unit,
    onBassBoostChange: (Int) -> Unit,
    onLoudnessChange: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    val info by stateFlow.collectAsStateWithLifecycle()
    SheetScaffold(
        title = stringResource(R.string.equalizer),
        icon = Icons.Default.GraphicEq,
        onDismiss = onDismiss,
        headerActions = {
            Switch(
                checked = info.enabled,
                onCheckedChange = onEnabledChange,
                enabled = info.available,
            )
        },
    ) {
        Column(
            modifier = Modifier
                .weight(1f, fill = false)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(bottom = 24.dp),
        ) {
            if (!info.available) {
                Text(
                    text = stringResource(R.string.equalizer_not_available),
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f),
                    ),
                    modifier = Modifier.padding(vertical = 16.dp),
                )
                return@Column
            }
            val controlsEnabled = info.enabled
            val contentAlpha = if (controlsEnabled) 1f else 0.4f

            // ── Preset chips ──
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                PresetChip(
                    label = stringResource(R.string.equalizer_preset_custom),
                    selected = info.currentPreset < 0,
                    enabled = controlsEnabled,
                    onClick = { onPresetSelect(-1) },
                )
                info.presets.forEachIndexed { index, name ->
                    PresetChip(
                        label = name,
                        selected = info.currentPreset == index,
                        enabled = controlsEnabled,
                        onClick = { onPresetSelect(index) },
                    )
                }
            }

            // ── Band sliders ──
            Spacer(modifier = Modifier.height(12.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = stringResource(R.string.equalizer_bands),
                    style = MaterialTheme.typography.labelLarge.copy(
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f * contentAlpha),
                        fontWeight = FontWeight.Bold,
                    ),
                )
                Text(
                    text = stringResource(R.string.reset),
                    color = MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha),
                    style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                    modifier = Modifier
                        .clip(RoundedCornerShape(4.dp))
                        .clickable(enabled = controlsEnabled, onClick = onReset)
                        .padding(horizontal = 8.dp, vertical = 2.dp),
                )
            }
            // ── Band bank: fixed-width slim columns; scrolls horizontally on
            // small screens instead of cramming all 10 bands into the width.
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState())
                    .padding(top = 8.dp),
                horizontalArrangement = Arrangement.spacedBy(2.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                info.bands.forEach { band ->
                    BandColumn(
                        band = band,
                        minMb = info.minLevelMb,
                        maxMb = info.maxLevelMb,
                        enabled = controlsEnabled,
                        onCommit = { levelMb -> onBandChange(band.index, levelMb) },
                    )
                }
            }

            // ── Bass boost / loudness ──
            if (info.bassBoostAvailable || info.loudnessAvailable) {
                Spacer(modifier = Modifier.height(8.dp))
                HorizontalDivider(color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f))
                Spacer(modifier = Modifier.height(8.dp))
            }
            if (info.bassBoostAvailable) {
                EffectSlider(
                    label = stringResource(R.string.bass_boost),
                    value = info.bassBoostStrength,
                    maxValue = 1000,
                    valueLabel = { "${it / 10}%" },
                    enabled = controlsEnabled,
                    onCommit = onBassBoostChange,
                )
            }
            if (info.loudnessAvailable) {
                EffectSlider(
                    label = stringResource(R.string.loudness_enhancer),
                    value = info.loudnessGainMb,
                    maxValue = 1000,
                    valueLabel = { formatDb(it) },
                    enabled = controlsEnabled,
                    onCommit = onLoudnessChange,
                )
            }
        }
    }
}

@Composable
private fun PresetChip(
    label: String,
    selected: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val contentAlpha = if (enabled) 1f else 0.4f
    Text(
        text = label,
        style = MaterialTheme.typography.labelLarge,
        color = if (selected) MaterialTheme.colorScheme.primary.copy(alpha = contentAlpha)
        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.8f * contentAlpha),
        fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
        modifier = Modifier
            .clip(RoundedCornerShape(16.dp))
            .background(
                if (selected) MaterialTheme.colorScheme.primary.copy(alpha = 0.15f * contentAlpha)
                else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.06f),
            )
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    )
}

/**
 * One vertical EQ band: dB readout on top, bottom-to-top slider, frequency
 * label underneath — the classic graphic-equalizer column. Fixed-width so the
 * bank keeps a uniform slim look and overflows into a horizontal scroll.
 */
@Composable
private fun BandColumn(
    band: EqualizerBand,
    minMb: Int,
    maxMb: Int,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    // Reseeded whenever the committed level changes (preset switch, reset).
    var level by remember(band.levelMb) { mutableFloatStateOf(band.levelMb.toFloat()) }
    Column(
        modifier = Modifier.width(40.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            text = formatDbShort(level.roundToInt()),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
        )
        VerticalSlider(
            value = level,
            onValueChange = { level = it },
            onValueChangeFinished = { onCommit(level.roundToInt()) },
            valueRange = minMb.toFloat()..maxMb.toFloat(),
            enabled = enabled,
            length = 170.dp,
        )
        Text(
            text = formatFrequencyShort(band.centerFreqHz),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
        )
    }
}

/**
 * A slim [Slider] rotated to run bottom-to-top. The rotation only transforms
 * the drawing/touch, so a custom layout swaps the measured width/height back
 * and recenters the placeable — the standard Compose vertical-slider recipe.
 * A 3dp track and a flat fader-cap thumb replace the chunky M3 defaults.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun VerticalSlider(
    value: Float,
    onValueChange: (Float) -> Unit,
    onValueChangeFinished: () -> Unit,
    valueRange: ClosedFloatingPointRange<Float>,
    enabled: Boolean,
    length: androidx.compose.ui.unit.Dp,
) {
    Slider(
        value = value,
        onValueChange = onValueChange,
        onValueChangeFinished = onValueChangeFinished,
        valueRange = valueRange,
        enabled = enabled,
        thumb = {
            // Drawn horizontal (8x22), appears as a wide flat cap once rotated.
            Box(
                modifier = Modifier
                    .size(width = 8.dp, height = 22.dp)
                    .clip(RoundedCornerShape(4.dp))
                    .background(
                        if (enabled) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f),
                    ),
            )
        },
        track = { sliderState ->
            SliderDefaults.Track(
                sliderState = sliderState,
                enabled = enabled,
                drawStopIndicator = null,
                thumbTrackGapSize = 2.dp,
                modifier = Modifier.height(3.dp),
            )
        },
        modifier = Modifier
            .graphicsLayer { rotationZ = 270f }
            .layout { measurable, constraints ->
                val placeable = measurable.measure(
                    Constraints(
                        minWidth = constraints.minHeight,
                        maxWidth = constraints.maxHeight,
                        minHeight = constraints.minWidth,
                        maxHeight = constraints.maxWidth,
                    ),
                )
                layout(placeable.height, placeable.width) {
                    placeable.place(
                        x = -(placeable.width - placeable.height) / 2,
                        y = (placeable.width - placeable.height) / 2,
                    )
                }
            }
            .width(length),
    )
}

@Composable
private fun EffectSlider(
    label: String,
    value: Int,
    maxValue: Int,
    valueLabel: (Int) -> String,
    enabled: Boolean,
    onCommit: (Int) -> Unit,
) {
    var current by remember(value) { mutableFloatStateOf(value.toFloat()) }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge.copy(
                color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
                fontWeight = FontWeight.Bold,
            ),
        )
        Text(
            text = valueLabel(current.roundToInt()),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = if (enabled) 0.7f else 0.3f),
        )
    }
    Slider(
        value = current,
        onValueChange = { current = it },
        onValueChangeFinished = { onCommit(current.roundToInt()) },
        valueRange = 0f..maxValue.toFloat(),
        enabled = enabled,
        modifier = Modifier.fillMaxWidth(),
    )
}

private fun formatFrequencyShort(hz: Int): String =
    if (hz >= 1000) "${hz / 1000}k" else "$hz"

/** Compact ±dB readout that fits a narrow band column, e.g. "+3". */
private fun formatDbShort(mb: Int): String =
    String.format(Locale.US, "%+d", (mb / 100f).roundToInt())

private fun formatDb(mb: Int): String =
    String.format(Locale.US, "%+.1f dB", mb / 100f)

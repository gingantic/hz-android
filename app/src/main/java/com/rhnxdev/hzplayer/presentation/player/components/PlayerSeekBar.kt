package com.rhnxdev.hzplayer.presentation.player.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.core.util.formatDuration
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun PlayerSeekBar(
    currentPosition: Long,
    duration: Long,
    bufferedPercentage: Int,
    onSeek: (Long) -> Unit,
    onSeekStart: () -> Unit,
    onSeekEnd: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val positionFraction = if (duration > 0) {
        (currentPosition.toFloat() / duration.toFloat()).coerceIn(0f, 1f)
    } else 0f

    Column(modifier = modifier.fillMaxWidth()) {
        // Buffered progress indicator
        if (bufferedPercentage > 0) {
            androidx.compose.material3.LinearProgressIndicator(
                progress = { bufferedPercentage / 100f },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(2.dp)
                    .padding(horizontal = Spacing.lg),
                color = MaterialTheme.colorScheme.surfaceVariant,
                trackColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.3f),
            )
        }

        Slider(
            value = positionFraction,
            onValueChange = { fraction ->
                val pos = (fraction * duration).toLong()
                onSeek(pos)
            },
            onValueChangeFinished = onSeekEnd,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
            colors = SliderDefaults.colors(
                thumbColor = MaterialTheme.colorScheme.primary,
                activeTrackColor = MaterialTheme.colorScheme.primary,
                inactiveTrackColor = MaterialTheme.colorScheme.surfaceVariant,
            ),
        )

        // Time labels
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = Spacing.lg),
        ) {
            Text(
                text = formatDuration(currentPosition),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = formatDuration(duration),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
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
            modifier = Modifier.padding(Spacing.lg),
        )
    }
}

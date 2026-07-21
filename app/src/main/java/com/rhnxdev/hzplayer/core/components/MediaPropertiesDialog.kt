package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.thumbnail.MediaInfoProbe

/**
 * A properties dialog that displays a list of label–value rows describing a
 * media item (file name, path, size, …). When [probeUri] is provided, the
 * container/codec metadata is probed asynchronously via the native FFmpeg
 * demuxer and appended below the static rows (with a brief loading indicator).
 */
@Composable
fun MediaPropertiesDialog(
    title: String,
    properties: List<Pair<String, String>>,
    onDismiss: () -> Unit,
    probeUri: String? = null,
) {
    val context = LocalContext.current

    var codecInfo by remember(probeUri) { mutableStateOf<Map<String, String>?>(null) }
    var probing by remember(probeUri) { mutableStateOf(probeUri != null) }

    LaunchedEffect(probeUri) {
        val uri = probeUri
        if (uri != null) {
            codecInfo = MediaInfoProbe.probe(context, uri)
        }
        probing = false
    }

    val codecRows = codecInfo?.let { formatCodecRows(it) } ?: emptyList()
    val allRows = remember(properties, codecRows) { properties + codecRows }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = title,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
            ) {
                allRows.forEachIndexed { index, (label, value) ->
                    if (index > 0) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Text(
                        text = label,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.SemiBold,
                    )
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = value,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    if (index < allRows.lastIndex || probing) {
                        Spacer(modifier = Modifier.height(12.dp))
                        HorizontalDivider()
                    }
                }

                if (probing) {
                    if (allRows.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(12.dp))
                    }
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.fillMaxWidth(),
                    ) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp,
                        )
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = stringResource(R.string.prop_loading_codecs),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}

/**
 * Convert a native probe result map into display rows. Only keys present in
 * [info] produce rows, so audio-only files won't show empty video rows.
 */
@Composable
private fun formatCodecRows(info: Map<String, String>): List<Pair<String, String>> {
    val container = stringResource(R.string.prop_container)
    val overallBitrate = stringResource(R.string.prop_overall_bitrate)
    val videoCodec = stringResource(R.string.prop_video_codec)
    val audioCodec = stringResource(R.string.prop_audio_codec)
    val frameRate = stringResource(R.string.prop_frame_rate)
    val videoBitrate = stringResource(R.string.prop_video_bitrate)
    val audioBitrate = stringResource(R.string.prop_audio_bitrate)
    val sampleRate = stringResource(R.string.prop_sample_rate)
    val channels = stringResource(R.string.prop_channels)
    val pixelFormat = stringResource(R.string.prop_pixel_format)

    return buildList {
        info["format_long"]?.let { add(container to it) }
            ?: info["format"]?.let { add(container to it) }
        info["bitrate"]?.toLongOrNull()?.let { add(overallBitrate to formatBitrate(it)) }

        info["video_codec"]?.let { codec ->
            val profile = info["video_profile"]
            add(videoCodec to if (profile != null) "$codec ($profile)" else codec)
        }
        info["video_fps"]?.let { add(frameRate to "$it fps") }
        info["video_bitrate"]?.toLongOrNull()?.let { add(videoBitrate to formatBitrate(it)) }
        info["video_pix_fmt"]?.let { add(pixelFormat to it) }

        info["audio_codec"]?.let { add(audioCodec to it) }
        info["audio_bitrate"]?.toLongOrNull()?.let { add(audioBitrate to formatBitrate(it)) }
        info["audio_sample_rate"]?.let { add(sampleRate to formatSampleRate(it)) }
        info["audio_channels"]?.toIntOrNull()?.let { add(channels to formatChannels(it)) }
    }
}

/** Format a bits-per-second value into a human-readable string. */
private fun formatBitrate(bps: Long): String = when {
    bps >= 1_000_000 -> String.format("%.2f Mbps", bps / 1_000_000.0)
    bps >= 1_000 -> String.format("%.0f kbps", bps / 1_000.0)
    else -> "$bps bps"
}

/** Format a sample-rate string (in Hz) into kHz when it divides evenly. */
private fun formatSampleRate(hz: String): String {
    val n = hz.toIntOrNull() ?: return "$hz Hz"
    return if (n % 1000 == 0) "${n / 1000} kHz" else "$n Hz"
}

/** Map a channel count to a common layout name. */
private fun formatChannels(count: Int): String = when (count) {
    1 -> "Mono"
    2 -> "Stereo"
    6 -> "5.1"
    8 -> "7.1"
    else -> "$count ch"
}

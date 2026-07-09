package com.rhnxdev.hzplayer.presentation.player.components

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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.rhnxdev.hzplayer.domain.model.DebugStats

private val bgAlpha = Color.Black.copy(alpha = 0.88f)
private val mono = FontFamily.Monospace
private val labelColor = Color(0xFFAAAAAA)
private val valueColor = Color(0xFFE0E0E0)

@Composable
fun DebugOverlay(
    stats: DebugStats,
    onDismiss: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Box(
        modifier = modifier
            .fillMaxSize()
            .clickable(enabled = false) { /* consume taps */ },
        contentAlignment = Alignment.TopEnd,
    ) {
        Column(
            modifier = Modifier
                .padding(top = 56.dp, end = 12.dp)
                .width(300.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(bgAlpha),
        ) {
            // Title + X
            Row(
                modifier = Modifier.fillMaxWidth().padding(start = 10.dp, top = 4.dp, end = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "Stats for nerds",
                    color = Color(0xFF8AB4F8),
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.weight(1f),
                )
                IconButton(onClick = onDismiss, modifier = Modifier.size(24.dp)) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White.copy(alpha = 0.7f), modifier = Modifier.size(14.dp))
                }
            }

            // Scroll stats
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(260.dp)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 10.dp, vertical = 2.dp),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                SectionHeader("Video")
                StatRow("Codec", stats.videoCodec.ifEmpty { stats.videoCodecMime })
                StatRow("Decoder", stats.videoDecoderLabel)
                StatRow("Resolution", stats.resolution)
                StatRow("Avg bitrate", stats.videoBitrateEstimated)
                StatRow("FPS (rendered)", stats.renderedFps)
                StatRow("Dropped", stats.droppedFrames)
                StatRow("Color", stats.colorInfo)
                StatRow("HDR", stats.hdrInfo)

                Spacer(Modifier.height(2.dp))
                SectionHeader("Audio")
                StatRow("Codec", stats.audioCodec.ifEmpty { stats.audioCodecMime })
                StatRow("Decoder", stats.audioDecoderLabel)
                StatRow("Avg bitrate", stats.audioBitrateEstimated)
                StatRow("Sample rate", stats.sampleRate)
                StatRow("Channels", stats.channelCount)
                StatRow("Language", stats.audioLanguage)

                Spacer(Modifier.height(2.dp))
                SectionHeader("Network")
                StatRow("Speed", stats.networkSpeed)
                StatRow("Downloaded", stats.bytesDownloaded)

                Spacer(Modifier.height(2.dp))
                SectionHeader("Device")
                StatRow("Model", stats.deviceModel)
                StatRow("OS", stats.androidVersion)
                StatRow("SoC", stats.soCInfo)

                Spacer(Modifier.height(6.dp))
            }
        }
    }
}

@Composable
private fun SectionHeader(text: String) {
    Text(
        text = text,
        color = Color(0xFF8AB4F8),
        fontSize = 10.sp,
        fontWeight = FontWeight.SemiBold,
        modifier = Modifier.padding(top = 2.dp, bottom = 0.dp),
    )
}

@Composable
private fun StatRow(label: String, value: String) {
    if (value.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 0.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(text = label, color = labelColor, fontSize = 9.sp, fontFamily = mono)
        Text(text = value, color = valueColor, fontSize = 9.sp, fontFamily = mono, fontWeight = FontWeight.Medium, maxLines = 1)
    }
}


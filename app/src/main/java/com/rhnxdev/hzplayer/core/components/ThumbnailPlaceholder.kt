package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons

enum class MediaType {
    VIDEO,
    AUDIO,
    FOLDER,
    FILE,
}

@Composable
fun ThumbnailPlaceholder(
    mediaType: MediaType,
    modifier: Modifier = Modifier.fillMaxSize(),
) {
    val icon = when (mediaType) {
        MediaType.VIDEO -> HzPlayerIcons.Video
        MediaType.AUDIO -> HzPlayerIcons.Audio
        MediaType.FOLDER -> HzPlayerIcons.Folder
        MediaType.FILE -> HzPlayerIcons.Audio
    }
    val gradient = when (mediaType) {
        MediaType.VIDEO -> Gradient.VIDEO
        MediaType.AUDIO -> Gradient.AUDIO
        MediaType.FOLDER -> Gradient.FOLDER
        MediaType.FILE -> Gradient.FILE
    }

    Box(
        modifier = modifier
            .clip(MaterialTheme.shapes.medium)
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White.copy(alpha = 0.6f),
            modifier = Modifier.size(32.dp),
        )
    }
}

private object Gradient {
    val VIDEO = Brush.linearGradient(
        colors = listOf(
            Color(0xFF2D1B00),
            Color(0xFF1A1A2E),
        ),
    )
    val AUDIO = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1A2E1A),
            Color(0xFF002B36),
        ),
    )
    val FOLDER = Brush.linearGradient(
        colors = listOf(
            Color(0xFF1A1A2E),
            Color(0xFF2D1B00),
        ),
    )
    val FILE = Brush.linearGradient(
        colors = listOf(
            Color(0xFF2A2A2A),
            Color(0xFF1A1A1A),
        ),
    )
}

@Preview
@Composable
private fun VideoPlaceholderPreview() {
    Box(modifier = Modifier.size(200.dp, 112.dp)) {
        ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
    }
}

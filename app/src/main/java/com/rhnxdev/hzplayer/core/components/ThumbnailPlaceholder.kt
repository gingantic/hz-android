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
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.runtime.remember
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.domain.model.MediaType

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

    val primary = MaterialTheme.colorScheme.primary
    val primaryContainer = MaterialTheme.colorScheme.primaryContainer
    val secondaryContainer = MaterialTheme.colorScheme.secondaryContainer
    val surfaceVariant = MaterialTheme.colorScheme.surfaceVariant
    val isLight = MaterialTheme.colorScheme.background.luminance() > 0.5f

    val surface = MaterialTheme.colorScheme.surface
    val gradient = remember(mediaType, primary, primaryContainer, secondaryContainer, surfaceVariant, surface, isLight) {
        when (mediaType) {
            MediaType.VIDEO -> {
                if (isLight) {
                    Brush.linearGradient(
                        colors = listOf(
                            primaryContainer.copy(alpha = 0.8f),
                            secondaryContainer.copy(alpha = 0.5f),
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            primaryContainer.copy(alpha = 0.25f),
                            surface,
                        )
                    )
                }
            }
            MediaType.AUDIO -> {
                if (isLight) {
                    Brush.linearGradient(
                        colors = listOf(
                            secondaryContainer.copy(alpha = 0.8f),
                            primaryContainer.copy(alpha = 0.4f),
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            secondaryContainer.copy(alpha = 0.25f),
                            surface,
                        )
                    )
                }
            }
            MediaType.FOLDER -> {
                if (isLight) {
                    Brush.linearGradient(
                        colors = listOf(
                            surfaceVariant.copy(alpha = 0.9f),
                            primaryContainer.copy(alpha = 0.2f),
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            surface,
                            surfaceVariant,
                        )
                    )
                }
            }
            MediaType.FILE -> {
                if (isLight) {
                    Brush.linearGradient(
                        colors = listOf(
                            surfaceVariant.copy(alpha = 0.7f),
                            surfaceVariant.copy(alpha = 0.3f),
                        )
                    )
                } else {
                    Brush.linearGradient(
                        colors = listOf(
                            surface,
                            surfaceVariant,
                        )
                    )
                }
            }
        }
    }

    val iconTint = if (isLight) {
        MaterialTheme.colorScheme.primary.copy(alpha = 0.85f)
    } else {
        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.85f)
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
            tint = iconTint,
            modifier = Modifier.size(32.dp),
        )
    }
}

@Preview
@Composable
private fun VideoPlaceholderPreview() {
    Box(modifier = Modifier.size(200.dp, 112.dp)) {
        ThumbnailPlaceholder(mediaType = MediaType.VIDEO)
    }
}

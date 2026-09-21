package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.InsertDriveFile
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons
import com.rhnxdev.hzplayer.core.designsystem.mediaAccentColor
import com.rhnxdev.hzplayer.domain.model.MediaType
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Rounded, gradient-filled badge that identifies media by type.
 *
 * The accent comes from the theme palette (see [mediaAccentColor]) and is
 * darkened until a white glyph stays legible, so the badge holds up on pale
 * dynamic-colour accents.
 *
 * @param mediaType — selects the accent gradient
 * @param icon — glyph drawn in white over the gradient
 * @param size — badge edge length
 * @param iconSize — glyph size (defaults to half of [size])
 * @param cornerRadius — corner rounding (defaults to ~a third of [size])
 */
@Composable
fun MediaIconBadge(
    mediaType: MediaType,
    icon: ImageVector,
    modifier: Modifier = Modifier,
    size: Dp = 44.dp,
    iconSize: Dp = size * 0.5f,
    cornerRadius: Dp = size * 0.32f,
) {
    val rawAccent = mediaAccentColor(mediaType)
    val accent = remember(rawAccent) { rawAccent.forWhiteGlyph() }
    val gradient = remember(accent) {
        Brush.linearGradient(listOf(accent, lerp(accent, Color.Black, 0.45f)))
    }

    Box(
        modifier = modifier
            .size(size)
            .clip(RoundedCornerShape(cornerRadius))
            .background(gradient),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(iconSize),
        )
    }
}

/** Darkens [accent] so a white glyph keeps ~3:1 contrast on top. */
private fun Color.forWhiteGlyph(): Color {
    var shaded = this
    var steps = 0
    while (shaded.luminance() > 0.3f && steps++ < 8) {
        shaded = lerp(shaded, Color.Black, 0.22f)
    }
    return shaded
}

@PreviewLightDark
@Preview
@Composable
private fun MediaIconBadgePreview() {
    HzPlayerTheme {
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            MediaIconBadge(mediaType = MediaType.VIDEO, icon = HzPlayerIcons.Video)
            MediaIconBadge(mediaType = MediaType.AUDIO, icon = HzPlayerIcons.Audio)
            MediaIconBadge(mediaType = MediaType.FOLDER, icon = HzPlayerIcons.Folder)
            MediaIconBadge(
                mediaType = MediaType.FILE,
                icon = Icons.AutoMirrored.Filled.InsertDriveFile,
            )
        }
    }
}

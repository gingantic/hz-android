package com.rhnxdev.hzplayer.core.designsystem

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import com.rhnxdev.hzplayer.domain.model.MediaType

/**
 * Theme accent for a media kind: video → primary, audio → tertiary,
 * folder → secondary, file → neutral.
 *
 * Use it for fills and tints; for a white glyph on top, darken it first (see
 * [MediaIconBadge]).
 */
@Composable
fun mediaAccentColor(mediaType: MediaType): Color {
    val scheme = MaterialTheme.colorScheme
    val raw = when (mediaType) {
        MediaType.VIDEO -> scheme.primary
        MediaType.AUDIO -> scheme.tertiary
        MediaType.FOLDER -> scheme.secondary
        MediaType.FILE -> scheme.onSurfaceVariant
    }
    return remember(raw) { raw }
}

package com.rhnxdev.hzplayer.domain.model

import androidx.compose.runtime.Immutable
import androidx.compose.ui.graphics.Color

/**
 * User-configurable subtitle appearance settings.
 *
 * Applied via a custom [SubtitleOverlay] when ExoPlayer is active.
 * VlcEngine uses its own native subtitle rendering.
 */
@Immutable
data class SubtitleStyle(
    /** Font size in sp (typical range 14–32). */
    val fontSizeSp: Int = DEFAULT_FONT_SIZE,
    /** ARGB int for text color (e.g. 0xFFFFFFFF = white). */
    val textColorArgb: Int = DEFAULT_TEXT_COLOR,
    /** ARGB int for background color (e.g. 0x80000000 = semi-transparent black). 0 = transparent. */
    val backgroundColorArgb: Int = DEFAULT_BG_COLOR,
    /** Outline style: 0 = none, 1 = outline, 2 = drop shadow. */
    val edgeStyle: Int = 1,
    /** Whether subtitle rendering is enabled on the overlay. */
    val enabled: Boolean = true,
) {
    companion object {
        private const val DEFAULT_FONT_SIZE = 18
        private const val DEFAULT_TEXT_COLOR = 0xFFFFFFFF.toInt()
        private const val DEFAULT_BG_COLOR = 0x00000000.toInt() // transparent — no gray box

        val DEFAULT = SubtitleStyle()
    }
}

/** Convenience to get Compose [Color] from the ARGB int. */
val SubtitleStyle.textColor: Color get() = Color(textColorArgb)
/** Convenience to get Compose [Color] for background. */
val SubtitleStyle.backgroundColor: Color get() = Color(backgroundColorArgb)

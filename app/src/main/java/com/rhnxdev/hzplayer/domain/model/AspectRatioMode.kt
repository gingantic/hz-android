package com.rhnxdev.hzplayer.domain.model

/**
 * Video aspect ratio / scaling modes.
 *
 * Cycle through with [next] — each mode fills or constrains the
 * video differently within the available screen area.
 */
enum class AspectRatioMode(val label: String) {
    /** Fit entire video inside screen (letterbox/pillarbox). */
    AUTO("Auto"),

    /** Force 16:9 aspect ratio. */
    RATIO_16_9("16:9"),

    /** Force 4:3 aspect ratio. */
    RATIO_4_3("4:3");

    /** Cycle to the next mode in the enum order. */
    fun next(): AspectRatioMode {
        val values = entries
        return values[(ordinal + 1) % values.size]
    }
}

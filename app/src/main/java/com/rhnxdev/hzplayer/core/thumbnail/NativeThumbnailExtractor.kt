package com.rhnxdev.hzplayer.core.thumbnail

import android.graphics.Bitmap

/**
 * JNI bridge to the native FFmpeg thumbnail extractor.
 *
 * [extractThumbnail] decodes one frame at [positionPercent] (0.0–1.0) of the
 * video, scales it to fit within [maxWidth] (preserving aspect ratio), and
 * returns RGBA pixels as an [android.graphics.Bitmap].
 */
object NativeThumbnailExtractor {
    init {
        System.loadLibrary("thumbnail-extractor")
    }

    external fun extractThumbnail(
        bridge: RandomAccessBridge,
        positionPercent: Float,
        maxWidth: Int,
    ): Bitmap?
}

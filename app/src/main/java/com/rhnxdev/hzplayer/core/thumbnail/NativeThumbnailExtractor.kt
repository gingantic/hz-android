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
    private var loaded = false

    init {
        try {
            System.loadLibrary("thumbnail-extractor")
            loaded = true
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.w(TAG, "native extractor unavailable (${e.message})", e)
        }
    }

    /** Returns null when the native lib failed to load (e.g. x86_64 emulator). */
    fun extractThumbnail(
        bridge: ThumbnailSource,
        positionPercent: Float,
        maxWidth: Int,
    ): Bitmap? = if (loaded) nativeExtract(bridge, positionPercent, maxWidth) else null

    private external fun nativeExtract(
        bridge: ThumbnailSource,
        positionPercent: Float,
        maxWidth: Int,
    ): Bitmap?

    private const val TAG = "NativeThumbnailExtractor"
}

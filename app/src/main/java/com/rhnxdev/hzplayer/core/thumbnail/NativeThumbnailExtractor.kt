package com.rhnxdev.hzplayer.core.thumbnail

import android.graphics.Bitmap
import androidx.annotation.Keep

/**
 * JNI bridge to the native FFmpeg thumbnail extractor.
 *
 * [extractThumbnail] decodes one frame at [positionPercent] (0.0–1.0) of the
 * video, scales it to fit within [maxWidth] (preserving aspect ratio), and
 * returns RGBA pixels as an [android.graphics.Bitmap].
 */
@Keep
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
        fastMode: Boolean = false,
    ): Bitmap? {
        if (!loaded) return null
        return try {
            nativeExtract(bridge, positionPercent, maxWidth, fastMode)
        } catch (e: Exception) {
            android.util.Log.e(TAG, "nativeExtract failed", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e(TAG, "nativeExtract linkage error", e)
            loaded = false
            null
        }
    }

    private external fun nativeExtract(
        bridge: ThumbnailSource,
        positionPercent: Float,
        maxWidth: Int,
        fastMode: Boolean,
    ): Bitmap?

    private const val TAG = "NativeThumbnailExtractor"
}

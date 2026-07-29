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

    /**
     * Probes container + stream headers of [bridge] and returns codec metadata
     * as a map (e.g. `video_codec` → `h264`, `audio_codec` → `aac`). Only keys
     * the demuxer could determine are present. Returns null when the native lib
     * is unavailable or the source can't be parsed.
     */
    fun probeMediaInfo(bridge: ThumbnailSource): Map<String, String>? {
        if (!loaded) return null
        return try {
            nativeProbeMediaInfo(bridge)?.let { arr ->
                buildMap {
                    var i = 0
                    while (i + 1 < arr.size) {
                        put(arr[i], arr[i + 1])
                        i += 2
                    }
                }.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "nativeProbeMediaInfo failed", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e(TAG, "nativeProbeMediaInfo linkage error", e)
            loaded = false
            null
        }
    }

    private external fun nativeProbeMediaInfo(bridge: ThumbnailSource): Array<String>?

    /**
     * Probes container-level chapter markers of [bridge] and returns them as
     * (startMs, endMs, title) triples in playback order. Returns null when the
     * native lib is unavailable, the source can't be parsed, or it has no
     * chapters.
     */
    fun probeChapters(bridge: ThumbnailSource): List<Triple<Long, Long, String>>? {
        if (!loaded) return null
        return try {
            nativeProbeChapters(bridge)?.let { arr ->
                buildList {
                    var i = 0
                    while (i + 2 < arr.size) {
                        val start = arr[i].toLongOrNull()
                        val end = arr[i + 1].toLongOrNull()
                        if (start != null && end != null) {
                            add(Triple(start, end, arr[i + 2]))
                        }
                        i += 3
                    }
                }.takeIf { it.isNotEmpty() }
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "nativeProbeChapters failed", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e(TAG, "nativeProbeChapters linkage error", e)
            loaded = false
            null
        }
    }

    private external fun nativeProbeChapters(bridge: ThumbnailSource): Array<String>?

    private const val TAG = "NativeThumbnailExtractor"
}

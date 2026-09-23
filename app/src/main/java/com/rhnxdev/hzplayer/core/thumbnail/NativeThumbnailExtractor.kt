package com.rhnxdev.hzplayer.core.thumbnail

import android.graphics.Bitmap
import androidx.annotation.Keep
import com.rhnxdev.hzplayer.core.io.RandomAccessMediaSource

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
        bridge: RandomAccessMediaSource,
        positionPercent: Float,
        maxWidth: Int,
        fastMode: Boolean = false,
    ): Bitmap? = guarded("nativeExtract") {
        nativeExtract(bridge, positionPercent, maxWidth, fastMode)
    }

    private external fun nativeExtract(
        bridge: RandomAccessMediaSource,
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
    fun probeMediaInfo(bridge: RandomAccessMediaSource): Map<String, String>? =
        guarded("nativeProbeMediaInfo") {
            nativeProbeMediaInfo(bridge)?.let { arr ->
                buildMap {
                    var i = 0
                    while (i + 1 < arr.size) {
                        put(arr[i], arr[i + 1])
                        i += 2
                    }
                }.takeIf { it.isNotEmpty() }
            }
        }

    private external fun nativeProbeMediaInfo(bridge: RandomAccessMediaSource): Array<String>?

    /**
     * Probes container-level chapter markers of [bridge] and returns them as
     * (startMs, endMs, title) triples in playback order. Returns null when the
     * native lib is unavailable, the source can't be parsed, or it has no
     * chapters.
     */
    fun probeChapters(bridge: RandomAccessMediaSource): List<Triple<Long, Long, String>>? =
        guarded("nativeProbeChapters") {
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
        }

    private external fun nativeProbeChapters(bridge: RandomAccessMediaSource): Array<String>?

    /**
     * Run a native call behind the library-loaded check. A mid-flight
     * [UnsatisfiedLinkError] marks the lib unavailable so later calls short-circuit,
     * and any other failure is logged; both become null so callers degrade instead
     * of crashing.
     */
    private inline fun <T> guarded(name: String, block: () -> T?): T? {
        if (!loaded) return null
        return try {
            block()
        } catch (e: Exception) {
            android.util.Log.e(TAG, "$name failed", e)
            null
        } catch (e: UnsatisfiedLinkError) {
            android.util.Log.e(TAG, "$name linkage error", e)
            loaded = false
            null
        }
    }

    private const val TAG = "NativeThumbnailExtractor"
}

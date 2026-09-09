package com.rhnxdev.hzplayer.core.io

import androidx.annotation.Keep

/**
 * A random-access byte source the native FFmpeg code reads through its JNI
 * callback — the primary playback data path of the native player engine
 * (`libffplayer.so`) and of the thumbnail/probe extractors.
 *
 * The native side resolves [readAt] and [getSize] **by name** on the concrete
 * class, so all implementations must keep these method names intact after
 * R8/ProGuard shrinking. The [@Keep] annotation on the interface propagates
 * that constraint to every implementor.
 */
@Keep
interface RandomAccessMediaSource {
    /** Read up to [size] bytes at [position] into [buffer]; -1 at EOF. */
    @Keep fun readAt(position: Long, buffer: ByteArray, size: Int): Int

    /**
     * Request cancellation of a read that may currently be blocked on I/O.
     * Implementations must return promptly and must not release their backing
     * resources while an in-flight read still owns them.
     */
    @Keep fun abortRead() {}

    /** Total byte length of the source. */
    @Keep fun getSize(): Long
}

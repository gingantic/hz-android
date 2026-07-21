package com.rhnxdev.hzplayer.core.thumbnail

import androidx.annotation.Keep

/**
 * A random-access byte source the native FFmpeg extractor can read through its
 * JNI callback. The native side resolves [readAt] and [getSize] **by name** on
 * the concrete class, so all implementations must keep these method names
 * intact after R8/ProGuard shrinking. The [@Keep] annotation on the interface
 * propagates that constraint to every implementor.
 */
@Keep
interface ThumbnailSource {
    /** Read up to [size] bytes at [position] into [buffer]; -1 at EOF. */
    @Keep fun readAt(position: Long, buffer: ByteArray, size: Int): Int

    /** Total byte length of the source. */
    @Keep fun getSize(): Long
}

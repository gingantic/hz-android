package com.rhnxdev.hzplayer.core.thumbnail

/**
 * A random-access byte source the native FFmpeg extractor can read through its
 * JNI callback. The native side resolves [readAt] and [getSize] by name on the
 * concrete class, so any implementation works: [RandomAccessBridge] (SMB) or
 * [LocalRandomAccessBridge] (on-disk files).
 */
interface ThumbnailSource {
    /** Read up to [size] bytes at [position] into [buffer]; -1 at EOF. */
    fun readAt(position: Long, buffer: ByteArray, size: Int): Int

    /** Total byte length of the source. */
    fun getSize(): Long
}

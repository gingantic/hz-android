package com.rhnxdev.hzplayer.core.thumbnail

import java.io.Closeable
import java.io.RandomAccessFile

/**
 * [ThumbnailSource] backed by a local file. Used as a last-resort fallback for
 * formats [android.media.MediaMetadataRetriever] can't decode (e.g. some
 * MPEG-TS streams), routing the file through the native FFmpeg extractor.
 *
 * No prefetch/caching — local disk seeks are cheap, unlike the SMB bridge.
 */
class LocalRandomAccessBridge(path: String) : ThumbnailSource, Closeable {
    private val raf = RandomAccessFile(path, "r")
    private val size = raf.length()

    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (position >= this.size) return -1
        raf.seek(position)
        return raf.read(buffer, 0, size)
    }

    override fun getSize(): Long = size

    override fun close() {
        runCatching { raf.close() }
    }
}

package com.rhnxdev.hzplayer.core.thumbnail

import java.io.Closeable
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

/**
 * [ThumbnailSource] backed by a [FileChannel]. Works for both `file://` paths and
 * `content://` URIs (via a [android.os.ParcelFileDescriptor]'s FileDescriptor),
 * using positional reads so it stays seekable without moving a shared cursor.
 *
 * [onClose] lets callers release an owning ParcelFileDescriptor alongside the
 * channel.
 */
class ChannelRandomAccessBridge(
    private val channel: FileChannel,
    private val size: Long,
    private val onClose: (() -> Unit)? = null,
) : ThumbnailSource, Closeable {

    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (position >= this.size) return -1
        val bb = ByteBuffer.wrap(buffer, 0, size)
        var total = 0
        var pos = position
        while (bb.hasRemaining()) {
            val n = channel.read(bb, pos)
            if (n < 0) break
            total += n
            pos += n
        }
        return if (total == 0) -1 else total
    }

    override fun getSize(): Long = size

    override fun close() {
        runCatching { channel.close() }
        runCatching { onClose?.invoke() }
    }
}

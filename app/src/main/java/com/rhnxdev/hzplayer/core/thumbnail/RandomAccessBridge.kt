package com.rhnxdev.hzplayer.core.thumbnail

import jcifs.smb.SmbRandomAccessFile
import java.io.IOException

/**
 * Bridges the native FFmpeg extractor to the existing SMB random-access file.
 *
 * The native layer calls [readAt] to seek and read, [getSize] for file length,
 * and [close] when done. No caching here — the native AVIOContext buffer is
 * the only cache (256 KB default).
 */
class RandomAccessBridge(
    private val raf: SmbRandomAccessFile,
    private val fileSize: Long,
) {
    /** Seek to [position], then read up to [size] bytes into [buffer]. Returns bytes read, or -1 on error. */
    @Throws(IOException::class)
    fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        raf.seek(position)
        var total = 0
        while (total < size) {
            val n = raf.read(buffer, total, size - total)
            if (n < 0) break
            total += n
        }
        return total
    }

    fun getSize(): Long = fileSize

    fun close() {
        raf.close()
    }
}

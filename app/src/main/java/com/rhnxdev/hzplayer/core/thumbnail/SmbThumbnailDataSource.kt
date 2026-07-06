package com.rhnxdev.hzplayer.core.thumbnail

import android.net.Uri
import android.media.MediaDataSource
import android.util.Log
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import java.io.Closeable

/**
 * A [MediaDataSource] backed by SMB with a block read cache.
 *
 * `MediaMetadataRetriever` issues sparse random reads into the file (header,
 * moov atom, keyframe offset, etc.). This data source downloads small
 * 64 KB blocks on demand, caching the 8 most recently accessed blocks
 * in an LRU. Only the bytes actually needed by the decoder are transferred,
 * making it suitable for large remote SMB videos over high-latency links.
 */
class SmbThumbnailDataSource(
    private val uri: String,
    private val context: android.content.Context,
) : MediaDataSource(), Closeable {

    private var fileLength: Long = -1
    private var smbFile: SmbFile? = null
    private var randomAccessFile: SmbRandomAccessFile? = null

    /** Block cache: most recently used at tail. */
    private data class Block(val start: Long, val data: ByteArray)
    private val cache = object : LinkedHashMap<Long, Block>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, Block>?): Boolean =
            size > MAX_CACHED_BLOCKS
    }

    override fun readAt(position: Long, buffer: ByteArray, offset: Int, size: Int): Int {
        if (position < 0) throw IllegalArgumentException("position < 0: $position")
        if (position >= getSize()) return -1

        val maxRead = (size.toLong()).coerceAtMost(getSize() - position).toInt()
        if (maxRead <= 0) return -1

        var remaining = maxRead
        var pos = position

        while (remaining > 0) {
            val blockStart = (pos / BLOCK_SIZE) * BLOCK_SIZE
            val block = cache[blockStart]

            if (block != null) {
                // Cache hit
                val blockOffset = (pos - block.start).toInt()
                val toCopy = (block.data.size - blockOffset).coerceAtMost(remaining)
                buffer.put(block.data, blockOffset, offset + (maxRead - remaining), toCopy)
                remaining -= toCopy
                pos += toCopy
            } else {
                // Cache miss — fetch block
                val blockLen = (BLOCK_SIZE.toLong()).coerceAtMost(getSize() - blockStart).toInt()
                if (blockLen <= 0) break
                val blockData = ByteArray(blockLen)
                readRaw(blockStart, blockData, 0, blockLen)
                cache[blockStart] = Block(blockStart, blockData)

                // Loop will pick it up from cache next iteration
            }
        }

        return maxRead - remaining
    }

    private fun ByteArray.put(src: ByteArray, srcPos: Int, dstPos: Int, len: Int) {
        src.copyInto(this, dstPos, srcPos, srcPos + len)
    }

    /**
     * Read [len] bytes at [position] directly from SMB, bypassing cache.
     */
    private fun readRaw(position: Long, buffer: ByteArray, offset: Int, len: Int) {
        var attempts = 0
        while (attempts < 2) {
            try {
                ensureFileOpen()
                val raf = randomAccessFile ?: return

                raf.seek(position)
                var remaining = len
                var off = offset
                while (remaining > 0) {
                    val n = raf.read(buffer, off, remaining)
                    if (n < 0) break
                    remaining -= n
                    off += n
                }
                return // Success!
            } catch (e: Exception) {
                attempts++
                Log.w(TAG, "readRaw failed (attempt $attempts/2) at position $position for $uri: ${e.message}")
                closeFile()
                if (attempts >= 2) {
                    Log.e(TAG, "readRaw all attempts failed at position $position for $uri", e)
                }
            }
        }
    }

    private fun ensureFileOpen() {
        if (randomAccessFile != null) return

        val androidUri = Uri.parse(uri)
        val userInfo = androidUri.userInfo ?: ""
        val username = Uri.decode(userInfo.substringBefore(':'))
        val password = Uri.decode(userInfo.substringAfter(':', ""))
        val host = androidUri.host ?: throw java.io.IOException("No host in URI: $uri")
        val port = androidUri.port.takeIf { it > 0 } ?: 445
        val path = androidUri.path ?: "/"

        val cifsCtx = com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
            .borrowSmbThumbnailContext(host, port, username, password)
        val cleanUrl = "smb://$host:$port$path"

        Log.d(TAG, "ensureFileOpen: opening SmbRandomAccessFile for $cleanUrl")
        val file = SmbFile(cleanUrl, cifsCtx)
        if (fileLength < 0) {
            fileLength = file.length()
        }
        randomAccessFile = SmbRandomAccessFile(file, "r")
        smbFile = file
    }

    override fun getSize(): Long {
        if (fileLength < 0) {
            try {
                ensureFileOpen()
            } catch (_: Exception) { }
        }
        return fileLength.coerceAtLeast(0)
    }

    private fun closeFile() {
        try {
            randomAccessFile?.close()
        } catch (_: Exception) {}
        randomAccessFile = null
        smbFile = null
    }

    override fun close() {
        closeFile()
        cache.clear()
        fileLength = -1
    }

    companion object {
        private const val TAG = "SmbThumbnailDS"
        private const val BLOCK_SIZE = 128 * 1024 // 128 KB
        private const val MAX_CACHED_BLOCKS = 16
    }
}

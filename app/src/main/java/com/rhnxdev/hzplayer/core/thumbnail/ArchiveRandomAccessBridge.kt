package com.rhnxdev.hzplayer.core.thumbnail

import androidx.annotation.Keep
import com.rhnxdev.hzplayer.data.datasource.archive.ArchiveNative
import java.io.Closeable
import java.io.IOException

/**
 * [ThumbnailSource] backed by a single entry inside a compressed archive
 * (zip/7z/rar/tar/iso/etc.) via [ArchiveNative] (libarchive).
 *
 * Employs a sliding-window block cache (24 x 256KB = 6MB) to absorb local
 * backward/forward seeks (interleaved audio/video chunk demuxing, header probes)
 * directly from memory without triggering expensive libarchive reopen+decompression cycles.
 */
@Keep
class ArchiveRandomAccessBridge(
    private val containerPath: String,
    private val entryName: String,
    private val password: String? = null,
) : ThumbnailSource, Closeable {

    companion object {
        private const val BLOCK_SIZE = 256 * 1024 // 256 KB per block
        private const val MAX_CACHED_BLOCKS = 64  // 16 MB sliding-window cache
        private const val PINNED_HEADER_BLOCKS = 8 // 2 MB permanently pinned header
        private const val PINNED_TAIL_BLOCKS = 8   // 2 MB permanently pinned trailer/cues
    }

    private var handle: Long = 0L
    private val size: Long
    private var currentPos: Long = 0L
    private val lock = Any()

    private class CachedBlock(val data: ByteArray, val length: Int)

    // Pinned critical blocks (headers / trailer moov / cues) never evicted
    private val pinnedBlocks = HashMap<Long, CachedBlock>()
    private val totalBlocks: Long

    // LinkedHashMap with access-order for LRU caching
    private val blockCache = object : LinkedHashMap<Long, CachedBlock>(MAX_CACHED_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedBlock>?): Boolean {
            return size > MAX_CACHED_BLOCKS
        }
    }

    init {
        handle = ArchiveNative.nativeOpen(containerPath, entryName, password)
        if (handle == 0L) {
            throw IOException("Cannot open entry $entryName in $containerPath")
        }
        size = ArchiveNative.nativeLength(handle)
        totalBlocks = if (size > 0) (size + BLOCK_SIZE - 1) / BLOCK_SIZE else 0L
    }

    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int = synchronized(lock) {
        if (handle == 0L) return -1
        if (this.size in 0..position) return -1

        val bytesToRead = size.toLong().coerceAtMost(if (this.size > 0) this.size - position else size.toLong()).toInt()
        if (bytesToRead <= 0) return if (this.size in 0..position) -1 else 0

        var bytesCopied = 0
        var currentOffset = position

        while (bytesCopied < bytesToRead) {
            val blockIdx = currentOffset / BLOCK_SIZE
            val blockStart = blockIdx * BLOCK_SIZE
            val offsetInBlock = (currentOffset - blockStart).toInt()

            val block = getOrLoadBlock(blockIdx) ?: break
            if (offsetInBlock >= block.length) break // EOF

            val availableInBlock = block.length - offsetInBlock
            val chunk = (bytesToRead - bytesCopied).coerceAtMost(availableInBlock)
            System.arraycopy(block.data, offsetInBlock, buffer, bytesCopied, chunk)

            bytesCopied += chunk
            currentOffset += chunk

            if (block.length < BLOCK_SIZE && chunk == availableInBlock) {
                break // End of entry
            }
        }

        return if (bytesCopied == 0 && this.size in 0..position) -1 else bytesCopied
    }

    private fun getOrLoadBlock(blockIdx: Long): CachedBlock? {
        // 1. Check pinned critical blocks first (0ms latency, zero libarchive seek)
        pinnedBlocks[blockIdx]?.let { return it }

        // 2. Check LRU sliding-window cache
        blockCache[blockIdx]?.let { return it }

        if (handle == 0L) return null
        val targetPos = blockIdx * BLOCK_SIZE

        if (currentPos != targetPos) {
            if (!ArchiveNative.nativeSeek(handle, targetPos)) {
                return null
            }
            currentPos = targetPos
        }

        val buf = ByteArray(BLOCK_SIZE)
        var totalRead = 0
        while (totalRead < BLOCK_SIZE) {
            val n = ArchiveNative.nativeRead(handle, buf, totalRead, BLOCK_SIZE - totalRead)
            if (n <= 0) break
            totalRead += n
            currentPos += n
        }

        if (totalRead <= 0) return null

        val cached = CachedBlock(buf, totalRead)

        // Pin first 2MB and last 2MB permanently
        val isHeader = blockIdx < PINNED_HEADER_BLOCKS
        val isTail = totalBlocks > 0 && blockIdx >= (totalBlocks - PINNED_TAIL_BLOCKS)
        if (isHeader || isTail) {
            pinnedBlocks[blockIdx] = cached
        } else {
            blockCache[blockIdx] = cached
        }
        return cached
    }

    override fun getSize(): Long = size

    override fun close() = synchronized(lock) {
        pinnedBlocks.clear()
        blockCache.clear()
        if (handle != 0L) {
            ArchiveNative.nativeClose(handle)
            handle = 0L
        }
    }
}

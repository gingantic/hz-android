package com.rhnxdev.hzplayer.core.thumbnail

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.locks.ReentrantLock

/**
 * Bridges the native FFmpeg extractor to SMB.
 *
 * Implements concurrent read-ahead (prefetching) to maximize bandwidth utilization
 * and overcome network latency when loading large stream headers (e.g. 50MB+ MP4 moov atom).
 */
class RandomAccessBridge(
    private val file: SmbFile,
    private val fileSize: Long,
) : ThumbnailSource {
    companion object {
        private const val BLOCK_SIZE = 1024 * 1024 // 1 MB
        private const val PREFETCH_COUNT = 3        // Prefetch up to 3 blocks ahead
        private const val MAX_HANDLES = 3           // Number of persistent file handles
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var closed = false

    // Cache of block index -> async loading job
    private val cache = mutableMapOf<Long, Deferred<BlockData>>()
    private var lastReadBlockIdx = -1L

    // Persistent file handle pool
    private val handles = mutableListOf<SmbRandomAccessFile>()
    private val handleLocks = mutableListOf<ReentrantLock>()

    init {
        try {
            for (i in 0 until MAX_HANDLES) {
                handles.add(SmbRandomAccessFile(file, "r"))
                handleLocks.add(ReentrantLock())
            }
        } catch (e: Exception) {
            close()
            throw e
        }
    }

    class BlockData(val bytes: ByteArray, val length: Int)

    /** Read up to [size] bytes at [position] into [buffer]. */
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (closed) return -1 // bridge torn down — tell FFmpeg EOF, don't throw into JNI
        if (position >= fileSize) return -1

        val bytesToRead = size.toLong().coerceAtMost(fileSize - position).toInt()
        if (bytesToRead <= 0) return 0

        var bytesCopied = 0
        var currentPos = position

        while (bytesCopied < bytesToRead) {
            val blockIdx = currentPos / BLOCK_SIZE
            val blockOffset = blockIdx * BLOCK_SIZE
            val offsetInBlock = (currentPos - blockOffset).toInt()
            val remainingInBlock = BLOCK_SIZE - offsetInBlock
            val chunkToCopy = (bytesToRead - bytesCopied).coerceAtMost(remainingInBlock)

            // Fetch current block
            val blockData = try {
                getBlock(blockIdx)
            } catch (_: CancellationException) {
                // Close raced with an in-flight prefetch; stop reading.
                return -1
            }

            val srcOffset = offsetInBlock
            if (srcOffset < blockData.length) {
                val copySize = chunkToCopy.coerceAtMost(blockData.length - srcOffset)
                System.arraycopy(blockData.bytes, srcOffset, buffer, bytesCopied, copySize)
                bytesCopied += copySize
                currentPos += copySize
                if (copySize < chunkToCopy) {
                    break // Block EOF
                }
            } else {
                break // Offset out of bounds (EOF)
            }
        }

        // Trigger prefetching of subsequent blocks
        val currentBlockIdx = position / BLOCK_SIZE
        if (currentBlockIdx != lastReadBlockIdx) {
            lastReadBlockIdx = currentBlockIdx
            triggerPrefetches(currentBlockIdx)
        }

        return bytesCopied
    }

    private fun getBlock(blockIdx: Long): BlockData {
        val deferred = synchronized(cache) {
            cache.getOrPut(blockIdx) {
                scope.async {
                    readBlockFromFile(blockIdx)
                }
            }
        }
        return runBlocking {
            deferred.await()
        }
    }

    private fun triggerPrefetches(currentBlockIdx: Long) {
        synchronized(cache) {
            // Evict older blocks that are behind the active window to save memory
            val keysToRemove = cache.keys.filter { it < currentBlockIdx - 1 }
            for (key in keysToRemove) {
                cache.remove(key)
            }

            // Start prefetching next N blocks concurrently
            for (i in 1..PREFETCH_COUNT) {
                val nextBlockIdx = currentBlockIdx + i
                val nextOffset = nextBlockIdx * BLOCK_SIZE
                if (nextOffset < fileSize) {
                    cache.getOrPut(nextBlockIdx) {
                        scope.async {
                            readBlockFromFile(nextBlockIdx)
                        }
                    }
                }
            }
        }
    }

    private fun readBlockFromFile(blockIdx: Long): BlockData {
        val offset = blockIdx * BLOCK_SIZE
        val data = ByteArray(BLOCK_SIZE)
        var total = 0

        // Find a free handle or wait for one from the pool
        var handleIdx = -1
        while (handleIdx == -1) {
            for (i in handles.indices) {
                if (handleLocks[i].tryLock()) {
                    handleIdx = i
                    break
                }
            }
            if (handleIdx == -1) {
                Thread.sleep(5) // Wait a tiny bit and retry
            }
        }

        try {
            val raf = handles[handleIdx]
            raf.seek(offset)
            while (total < BLOCK_SIZE) {
                val toRead = BLOCK_SIZE - total
                val n = raf.read(data, total, toRead)
                if (n < 0) break
                total += n
            }
        } finally {
            handleLocks[handleIdx].unlock()
        }

        return BlockData(data, total)
    }

    override fun getSize(): Long = fileSize

    fun close() {
        closed = true
        for (i in handles.indices) {
            try {
                handles[i].close()
            } catch (e: Exception) {
                // ignore
            }
        }
        scope.cancel()
    }
}

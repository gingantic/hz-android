package com.rhnxdev.hzplayer.core.thumbnail

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.*
import java.io.IOException
import java.util.concurrent.Semaphore
import java.util.concurrent.locks.ReentrantLock

/**
 * Bridges the native FFmpeg extractor to SMB.
 *
 * Two modes:
 * - **Full** (default): concurrent read-ahead with 1 MB blocks and 3 handles.
 *   Best for playback or large sequential reads.
 * - **Lightweight** (`lightweight = true`): 256 KB blocks, no prefetch, single
 *   handle.  Minimises network bytes for random-access patterns like thumbnail
 *   extraction where FFmpeg reads a header then jumps to one keyframe.
 */
class RandomAccessBridge(
    private val file: SmbFile,
    private val fileSize: Long,
    lightweight: Boolean = false,
) : ThumbnailSource {
    companion object {
        private const val BLOCK_SIZE_FULL = 1024 * 1024   // 1 MB
        private const val BLOCK_SIZE_LIGHT = 256 * 1024   // 256 KB
        private const val PREFETCH_COUNT = 3              // Prefetch up to 3 blocks ahead (full mode)
    }

    private val blockSize = if (lightweight) BLOCK_SIZE_LIGHT else BLOCK_SIZE_FULL
    private val maxHandles = if (lightweight) 1 else 3
    private val prefetchEnabled = !lightweight

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    @Volatile private var closed = false

    // Cache of block index -> async loading job
    private val cache = mutableMapOf<Long, Deferred<BlockData>>()
    private var lastReadBlockIdx = -1L

    // Persistent file handle pool
    private val handles = mutableListOf<SmbRandomAccessFile>()
    private val handleLocks = mutableListOf<ReentrantLock>()
    private val handleSemaphore = Semaphore(maxHandles)

    init {
        try {
            for (i in 0 until maxHandles) {
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
            val blockIdx = currentPos / blockSize
            val blockOffset = blockIdx * blockSize
            val offsetInBlock = (currentPos - blockOffset).toInt()
            val remainingInBlock = blockSize - offsetInBlock
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

        // Trigger prefetching of subsequent blocks (full mode only)
        if (prefetchEnabled) {
            val currentBlockIdx = position / blockSize
            if (currentBlockIdx != lastReadBlockIdx) {
                lastReadBlockIdx = currentBlockIdx
                triggerPrefetches(currentBlockIdx)
            }
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
                val nextOffset = nextBlockIdx * blockSize
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
        val offset = blockIdx * blockSize
        val data = ByteArray(blockSize)
        var total = 0

        // Block on semaphore until a handle is available — avoids busy-wait.
        handleSemaphore.acquire()
        val handleIdx = try {
            var idx = -1
            for (i in handles.indices) {
                if (handleLocks[i].tryLock()) {
                    idx = i
                    break
                }
            }
            // Semaphore guarantees at least one lock is free.
            if (idx == -1) {
                for (i in handles.indices) {
                    handleLocks[i].lock()
                    idx = i
                    break
                }
            }
            idx
        } catch (e: Exception) {
            handleSemaphore.release()
            throw e
        }

        try {
            val raf = handles[handleIdx]
            raf.seek(offset)
            while (total < blockSize) {
                val toRead = blockSize - total
                val n = raf.read(data, total, toRead)
                if (n < 0) break
                total += n
            }
        } finally {
            handleLocks[handleIdx].unlock()
            handleSemaphore.release()
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

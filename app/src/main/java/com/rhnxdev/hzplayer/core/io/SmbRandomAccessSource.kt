package com.rhnxdev.hzplayer.core.io

import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.*
import java.io.Closeable
import java.io.IOException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Semaphore
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.locks.ReentrantLock

/**
 * Bridges native FFmpeg to an SMB file.
 *
 * Two modes:
 * - **Full** (default): concurrent read-ahead with 1 MB blocks and 3 handles.
 *   Best for playback or large sequential reads.
 * - **Lightweight** (`lightweight = true`): 256 KB blocks, no prefetch, single
 *   handle.  Minimises network bytes for random-access patterns like thumbnail
 *   extraction where FFmpeg reads a header then jumps to one keyframe.
 */
class SmbRandomAccessSource(
    private val file: SmbFile,
    private val fileSize: Long,
    private val lightweight: Boolean = false,
    private val onQuiesced: (() -> Unit)? = null,
) : RandomAccessMediaSource, Closeable {
    companion object {
        private const val BLOCK_SIZE_FULL = 1024 * 1024   // 1 MB
        private const val BLOCK_SIZE_LIGHT = 256 * 1024   // 256 KB
        private const val PREFETCH_COUNT = 3              // Prefetch up to 3 blocks ahead (full mode)
    }

    private val blockSize = if (lightweight) BLOCK_SIZE_LIGHT else BLOCK_SIZE_FULL
    private val maxHandles = if (lightweight) 1 else 3
    private val prefetchEnabled = !lightweight

    @Volatile private var closed = false
    @Volatile private var abortRequested = false
    private val activeReads = AtomicInteger(0)
    private val closeStarted = AtomicBoolean(false)
    private val handlesClosed = AtomicBoolean(false)
    private val quiescedNotified = AtomicBoolean(false)

    // Buffer pool to avoid frequent 256 KB / 1 MB allocations
    private val bufferPool = java.util.ArrayDeque<ByteArray>()

    // Cache of block index -> Future loading job (no runBlocking required)
    private val cache = mutableMapOf<Long, CompletableFuture<BlockData>>()
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

    private fun obtainBuffer(): ByteArray {
        synchronized(bufferPool) {
            return if (bufferPool.isNotEmpty()) bufferPool.removeLast() else ByteArray(blockSize)
        }
    }

    private fun recycleBuffer(buffer: ByteArray) {
        if (buffer.size == blockSize) {
            synchronized(bufferPool) {
                if (bufferPool.size < 4) {
                    bufferPool.addLast(buffer)
                }
            }
        }
    }

    /** Read up to [size] bytes at [position] into [buffer]. */
    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (abortRequested || closed) {
            // This is cancellation, not EOF. Throwing makes JniFile map it to
            // AVERROR_EXIT so native stop does not look like clean playback end.
            throw CancellationException("SMB read aborted")
        }
        if (position >= fileSize) return -1

        val bytesToRead = size.toLong().coerceAtMost(fileSize - position).toInt()
        if (bytesToRead <= 0) return 0

        var bytesCopied = 0
        var currentPos = position

        while (bytesCopied < bytesToRead) {
            if (abortRequested || closed) {
                throw CancellationException("SMB read aborted")
            }

            val blockIdx = currentPos / blockSize
            val blockOffset = blockIdx * blockSize
            val offsetInBlock = (currentPos - blockOffset).toInt()
            val remainingInBlock = blockSize - offsetInBlock
            val chunkToCopy = (bytesToRead - bytesCopied).coerceAtMost(remainingInBlock)

            // Fetch current block synchronously. getBlock removes failed
            // futures so a transient SMB failure can be retried on a later
            // seek/read instead of poisoning this block forever.
            val blockData = getBlock(blockIdx)

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
        if (prefetchEnabled && bytesCopied > 0) {
            val currentBlockIdx = position / blockSize
            if (currentBlockIdx != lastReadBlockIdx) {
                lastReadBlockIdx = currentBlockIdx
                triggerPrefetches(currentBlockIdx)
            }
        }

        // A zero-byte result is not a valid progress result for this bridge.
        // Return EOF only when the source has actually reached its end.
        return if (bytesCopied == 0) -1 else bytesCopied
    }

    private fun cancelPendingBlocks() {
        val pending = synchronized(cache) {
            val values = cache.values.toList()
            cache.clear()
            values
        }
        pending.forEach { future ->
            if (future.isDone) {
                runCatching { recycleBuffer(future.get().bytes) }
            } else {
                // CompletableFuture cancellation releases callers waiting in
                // get(); the supplier remains tracked by activeReads until its
                // jcifs operation actually returns.
                future.cancel(true)
            }
        }
    }

    private fun finishCloseIfQuiescent() {
        if (!closeStarted.get() || !closed || activeReads.get() != 0) return

        if (handlesClosed.compareAndSet(false, true)) {
            for (handle in handles) {
                try {
                    handle.close()
                } catch (_: Exception) {
                    // ignore
                }
            }
        }

        if (quiescedNotified.compareAndSet(false, true)) {
            runCatching { onQuiesced?.invoke() }
        }
    }

    private fun getBlock(blockIdx: Long): BlockData {
        if (closed || abortRequested) throw CancellationException("SMB source is closed")
        val future = synchronized(cache) {
            if (closed || abortRequested) throw CancellationException("SMB source is closed")
            cache.getOrPut(blockIdx) {
                CompletableFuture.supplyAsync({ readBlockFromFile(blockIdx) }, Dispatchers.IO.asExecutor())
            }
        }
        return try {
            future.get()
        } catch (error: Exception) {
            // Do not leave an exceptional/cancelled future in the block cache.
            // A later seek or retry must be able to issue a fresh SMB request.
            synchronized(cache) {
                if (cache[blockIdx] === future) {
                    cache.remove(blockIdx)
                }
            }
            throw error
        }
    }

    private fun triggerPrefetches(currentBlockIdx: Long) {
        synchronized(cache) {
            if (closed) return
            // Evict older blocks that are behind the active window to save memory
            val keysToRemove = cache.keys.filter { it < currentBlockIdx - 1 }
            for (key in keysToRemove) {
                cache.remove(key)?.thenAccept { blockData ->
                    recycleBuffer(blockData.bytes)
                }
            }

            // Start prefetching next N blocks concurrently
            for (i in 1..PREFETCH_COUNT) {
                val nextBlockIdx = currentBlockIdx + i
                val nextOffset = nextBlockIdx * blockSize
                if (nextOffset < fileSize) {
                    cache.getOrPut(nextBlockIdx) {
                        CompletableFuture.supplyAsync({ readBlockFromFile(nextBlockIdx) }, Dispatchers.IO.asExecutor())
                    }
                }
            }
        }
    }

    private fun readBlockFromFile(blockIdx: Long): BlockData {
        activeReads.incrementAndGet()
        try {
            if (closed) throw CancellationException("SMB source is closed")
            val offset = blockIdx * blockSize
            val data = obtainBuffer()
            var total = 0

            // Block on semaphore until a handle is available — avoids busy-wait.
            handleSemaphore.acquire()
            var handleIdx = -1
            try {
                if (closed) throw CancellationException("SMB source is closed")
                for (i in handles.indices) {
                    if (handleLocks[i].tryLock()) {
                        handleIdx = i
                        break
                    }
                }
                // Semaphore guarantees at least one lock is free, unless close has
                // started and cancelled the task between acquiring the semaphore and
                // selecting a handle.
                if (handleIdx == -1) {
                    for (i in handles.indices) {
                        handleLocks[i].lock()
                        handleIdx = i
                        break
                    }
                }

                if (closed) throw CancellationException("SMB source is closed")
                val raf = handles[handleIdx]
                raf.seek(offset)
                var zeroReadCount = 0
                while (total < blockSize) {
                    val toRead = blockSize - total
                    val n = raf.read(data, total, toRead)
                    if (n < 0) break
                    if (n == 0) {
                        zeroReadCount++
                        if (zeroReadCount >= 3) {
                            throw IOException("SMB read made no progress at offset $offset")
                        }
                        Thread.yield()
                        continue
                    }
                    zeroReadCount = 0
                    total += n
                }
            } finally {
                if (handleIdx >= 0) handleLocks[handleIdx].unlock()
                handleSemaphore.release()
            }

            val blockData = BlockData(data, total)
            if (closed) {
                // A canceled supplier has no future consumer left to recycle
                // this buffer after CompletableFuture cancellation.
                recycleBuffer(data)
                throw CancellationException("SMB source is closed")
            }
            return blockData
        } finally {
            activeReads.decrementAndGet()
            finishCloseIfQuiescent()
        }
    }

    override fun getSize(): Long = fileSize

    /**
     * Cancel callers waiting for a block without waiting for an already-running
     * jcifs socket operation. Resource closure is deferred until active block
     * reads have released their handle references.
     */
    override fun abortRead() {
        if (abortRequested) return
        abortRequested = true
        closed = true
        cancelPendingBlocks()
    }

    override fun close() {
        // Publish the reader-visible state before allowing a last worker to
        // close the handles. A concurrent read must fail its closed checks,
        // never enter the handle pool after cleanup begins.
        closed = true
        if (!closeStarted.compareAndSet(false, true)) return
        cancelPendingBlocks()
        // Do not take handleLocks here: a native teardown must not wait for a
        // network read. finishCloseIfQuiescent closes handles once their active
        // read references have drained and then releases the SMB lease.
        finishCloseIfQuiescent()
    }
}

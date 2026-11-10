package com.rhnxdev.hzplayer.core.io

import android.util.Log
import androidx.annotation.Keep
import com.rhnxdev.hzplayer.data.datasource.archive.ArchiveNative
import java.io.IOException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * [RandomAccessMediaSource] backed by a single entry inside a compressed archive
 * (zip/7z/rar/tar/iso/etc.) via [ArchiveNative] (libarchive).
 *
 * Implements a high-performance in-memory streaming architecture:
 * - 100% In-Memory (Zero Disk Writes): Eliminates flash storage wear and NAND degradation.
 * - Pinned Critical Blocks: First 2 MB (headers) and last 2 MB (trailers/cues) are pinned
 *   in RAM so container parsing and track metadata lookups never re-read from the network.
 * - Sliding Window LRU Cache: Up to 256 MB in RAM. Backward seeks, scrubbing, and
 *   replaying recent minutes are served instantly from memory in <0.1 ms with zero network latency.
 * - Fast-Forward Sequential Skipping: When seeking forward, advances the active decompressor
 *   linearly in memory without resetting to byte 0.
 * - Tail-Probe Protection: Drops optional cue/tag queries near EOF on Matroska files during startup,
 *   preventing full-stream downloads over constrained networks.
 */
@Keep
class ArchiveRandomAccessSource(
    private val containerPath: String,
    private val entryName: String,
    private val password: String? = null,
) : CloseableRandomAccessSource {

    companion object {
        private const val TAG = "ArchiveRandomAccessSource"
        private const val BLOCK_SIZE = 128 * 1024       // 128 KB per block for low-latency probes
        private const val PINNED_HEADER_BLOCKS = 16     // 2 MB permanently pinned header
        private const val PINNED_TAIL_BLOCKS = 16       // 2 MB permanently pinned trailer/cues
        private const val SHARED_IDLE_MS = 30_000L

        /**
         * Calculates safe maximum LRU blocks in RAM based on runtime available heap.
         * Allocates up to 30% of max heap, dynamically clamped between 64 MB (512 blocks)
         * and 256 MB (2048 blocks).
         */
        fun calculateOptimalCacheBlocks(): Int {
            val maxMemory = Runtime.getRuntime().maxMemory()
            val targetBytes = (maxMemory * 0.30).toLong()
            val clampedBytes = targetBytes.coerceIn(64L * 1024 * 1024, 256L * 1024 * 1024)
            return (clampedBytes / BLOCK_SIZE).toInt()
        }

        private val poolLock = Any()
        private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
            Thread(r, "ArchiveSessionReaper").apply { isDaemon = true }
        }
        private var sharedKey: Triple<String, String, String?>? = null
        private var sharedSource: ArchiveRandomAccessSource? = null
        private var sharedRefCount = 0
        private var sharedIdleFuture: ScheduledFuture<*>? = null

        /**
         * Acquires a reference-counted [CloseableRandomAccessSource] for `(containerPath, entryName, password)`.
         * Multiplexes multiple readers (probe, thumbnail, player) onto the same active decompressor session.
         */
        fun acquireShared(
            containerPath: String,
            entryName: String,
            password: String?,
        ): CloseableRandomAccessSource {
            val key = Triple(containerPath, entryName, password)
            var toClose: ArchiveRandomAccessSource? = null
            synchronized(poolLock) {
                sharedIdleFuture?.cancel(false)
                sharedIdleFuture = null
                val existing = sharedSource
                if (existing != null && sharedKey == key) {
                    sharedRefCount++
                    return SharedLease(existing)
                }
                if (existing != null && sharedRefCount == 0) {
                    toClose = existing
                    sharedSource = null
                    sharedKey = null
                }
            }
            runCatching { toClose?.closeInternal() }

            val created = ArchiveRandomAccessSource(containerPath, entryName, password)
            var orphanToClose: ArchiveRandomAccessSource? = null
            val leased = synchronized(poolLock) {
                if (sharedSource == null) {
                    sharedKey = key
                    sharedSource = created
                    sharedRefCount = 1
                    SharedLease(created)
                } else if (sharedKey == key) {
                    orphanToClose = created
                    sharedRefCount++
                    SharedLease(sharedSource!!)
                } else {
                    return created
                }
            }
            runCatching { orphanToClose?.closeInternal() }
            return leased
        }

        private fun releaseShared(source: ArchiveRandomAccessSource) {
            synchronized(poolLock) {
                if (source !== sharedSource) {
                    runCatching { source.closeInternal() }
                    return
                }
                sharedRefCount = (sharedRefCount - 1).coerceAtLeast(0)
                if (sharedRefCount == 0) {
                    sharedIdleFuture?.cancel(false)
                    sharedIdleFuture = reaper.schedule({
                        var expired: ArchiveRandomAccessSource? = null
                        synchronized(poolLock) {
                            if (sharedRefCount == 0 && sharedSource === source) {
                                expired = sharedSource
                                sharedSource = null
                                sharedKey = null
                            }
                        }
                        runCatching { expired?.closeInternal() }
                    }, SHARED_IDLE_MS, TimeUnit.MILLISECONDS)
                }
            }
        }

        private class SharedLease(
            private val delegate: ArchiveRandomAccessSource,
        ) : CloseableRandomAccessSource, RandomAccessMediaSource by delegate {
            private val closed = AtomicBoolean(false)
            override fun close() {
                if (closed.compareAndSet(false, true)) {
                    releaseShared(delegate)
                }
            }
        }
    }

    @Volatile private var handle: Long = 0L
    @Volatile private var closed = false
    private var remoteSource: CloseableRandomAccessSource? = null
    private val size: Long
    private var currentPos: Long = 0L
    private val cacheLock = Any()
    private val ioLock = Any()

    // Reusable scratch buffer for skipping decompressed bytes without GC churn
    private val skipBuffer = ByteArray(BLOCK_SIZE)

    private class CachedBlock(val data: ByteArray, val length: Int)

    // Pinned critical blocks (headers / trailer moov / cues) never evicted
    private val pinnedBlocks = HashMap<Long, CachedBlock>()
    private val totalBlocks: Long

    private val maxCachedBlocks: Int = calculateOptimalCacheBlocks()

    // LinkedHashMap with access-order for LRU caching in RAM (64 MB to 256 MB dynamic)
    private val blockCache = object : LinkedHashMap<Long, CachedBlock>(maxCachedBlocks, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedBlock>?): Boolean {
            return size > maxCachedBlocks
        }
    }

    init {
        if (isRemoteContainerUri(containerPath)) {
            val src = openRemoteRandomAccessSource(containerPath)
            remoteSource = src
            handle = ArchiveNative.nativeOpenBridge(src, containerPath, entryName, password)
            if (handle == 0L) {
                remoteSource = null
                runCatching { src.close() }
                throw IOException("Cannot open remote entry $entryName in $containerPath")
            }
        } else {
            handle = ArchiveNative.nativeOpen(containerPath, entryName, password)
            if (handle == 0L) {
                throw IOException("Cannot open entry $entryName in $containerPath")
            }
        }
        size = ArchiveNative.nativeLength(handle)
        totalBlocks = if (size > 0) (size + BLOCK_SIZE - 1) / BLOCK_SIZE else 0L
    }

    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (closed || handle == 0L) return -1
        if (this.size in 0..position) return -1
        Log.d(TAG, "readAt: pos=$position size=$size currentPos=$currentPos entry=$entryName")

        val isMkvOrWebm = entryName.endsWith(".mkv", ignoreCase = true) || entryName.endsWith(".webm", ignoreCase = true)
        if (isMkvOrWebm && this.size > 10_000_000L && position > this.size - 2_000_000L && currentPos < 5_000_000L) {
            // Optional trailer probe (Cues/Tags/Chapters at EOF).
            // Rejecting this prevents decompressing hundreds of megabytes over constrained networks.
            Log.i(TAG, "Skipping optional tail probe on $entryName at pos=$position (size=${this.size}) to prevent full-stream download")
            return -1
        }

        val bytesToRead = size.toLong().coerceAtMost(if (this.size > 0) this.size - position else size.toLong()).toInt()
        if (bytesToRead <= 0) return if (this.size in 0..position) -1 else 0

        var bytesCopied = 0
        var currentOffset = position

        while (bytesCopied < bytesToRead && !closed) {
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
        synchronized(cacheLock) {
            if (closed) return null
            pinnedBlocks[blockIdx]?.let { return it }
            blockCache[blockIdx]?.let { return it }
        }

        synchronized(ioLock) {
            synchronized(cacheLock) {
                pinnedBlocks[blockIdx]?.let { return it }
                blockCache[blockIdx]?.let { return it }
            }
            return readBlockFromNativeIoLocked(blockIdx)
        }
    }

    private fun readBlockFromNativeIoLocked(blockIdx: Long): CachedBlock? {
        val h = handle
        if (closed || h == 0L) return null
        val targetPos = blockIdx * BLOCK_SIZE
        Log.i(TAG, "readBlock: targetBlock=$blockIdx targetPos=$targetPos currentPos=$currentPos entry=$entryName")

        if (currentPos < targetPos) {
            // Forward jump in compressed stream: decompress intermediate blocks and cache them in RAM
            val currentBlock = currentPos / BLOCK_SIZE
            var b = currentBlock
            while (b < blockIdx && !closed && h != 0L) {
                var nRead = 0
                while (nRead < BLOCK_SIZE && !closed) {
                    val n = ArchiveNative.nativeRead(h, skipBuffer, nRead, BLOCK_SIZE - nRead)
                    if (n <= 0) break
                    nRead += n
                    currentPos += n
                }
                if (nRead > 0) {
                    // Cache intermediate block in RAM
                    val blockData = ByteArray(nRead)
                    System.arraycopy(skipBuffer, 0, blockData, 0, nRead)
                    val cached = CachedBlock(blockData, nRead)
                    synchronized(cacheLock) {
                        storeBlockLocked(b, cached)
                    }
                    b++
                } else {
                    if (!ArchiveNative.nativeSeek(h, targetPos)) return null
                    currentPos = targetPos
                    break
                }
            }
        } else if (currentPos > targetPos) {
            // Backward jump: check if block is already in RAM cache
            synchronized(cacheLock) {
                pinnedBlocks[blockIdx]?.let { return it }
                blockCache[blockIdx]?.let { return it }
            }
            if (!ArchiveNative.nativeSeek(h, targetPos)) {
                return null
            }
            currentPos = targetPos
        }

        val buf = ByteArray(BLOCK_SIZE)
        var totalRead = 0
        while (totalRead < BLOCK_SIZE && !closed) {
            val n = ArchiveNative.nativeRead(h, buf, totalRead, BLOCK_SIZE - totalRead)
            if (n <= 0) break
            totalRead += n
            currentPos += n
        }

        if (totalRead <= 0) return null

        val cached = CachedBlock(buf, totalRead)
        synchronized(cacheLock) {
            storeBlockLocked(blockIdx, cached)
        }
        return cached
    }

    private fun storeBlockLocked(blockIdx: Long, cached: CachedBlock) {
        val isHeader = blockIdx < PINNED_HEADER_BLOCKS
        val isTail = totalBlocks > 0 && blockIdx >= (totalBlocks - PINNED_TAIL_BLOCKS)
        if (isHeader || isTail) {
            pinnedBlocks[blockIdx] = cached
        } else {
            blockCache[blockIdx] = cached
        }
    }

    override fun getSize(): Long = size

    override fun close() {
        closeInternal()
    }

    private fun closeInternal() {
        closed = true
        synchronized(cacheLock) {
            pinnedBlocks.clear()
            blockCache.clear()
        }
        synchronized(ioLock) {
            if (handle != 0L) {
                ArchiveNative.nativeClose(handle)
                handle = 0L
            }
            remoteSource?.let { src ->
                remoteSource = null
                runCatching { src.close() }
            }
        }
    }
}

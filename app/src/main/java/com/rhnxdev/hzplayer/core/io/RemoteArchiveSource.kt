package com.rhnxdev.hzplayer.core.io

import android.net.Uri
import com.rhnxdev.hzplayer.core.util.userInfoPair
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.asExecutor
import kotlinx.coroutines.Dispatchers
import net.schmizz.sshj.sftp.RemoteFile
import net.schmizz.sshj.sftp.SFTPClient
import okhttp3.Credentials
import okhttp3.HttpUrl
import org.apache.commons.net.ftp.FTPClient
import java.io.Closeable
import java.io.IOException
import java.io.InputStream
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * A [RandomAccessMediaSource] that also implements [Closeable] so callers can deterministically
 * release pooled network connections when archive listing or playback finishes.
 */
interface CloseableRandomAccessSource : RandomAccessMediaSource, Closeable

/**
 * True when [containerPath] is a remote URI (`smb://`, `sftp://`, `ftp://`, `webdav://`,
 * `webdavs://`, `http://`, `https://`) rather than a local filesystem path.
 */
fun isRemoteContainerUri(containerPath: String): Boolean {
    val scheme = containerPath.substringBefore("://", missingDelimiterValue = "").lowercase()
    return scheme in REMOTE_CONTAINER_SCHEMES
}

private val REMOTE_CONTAINER_SCHEMES = setOf(
    "smb", "sftp", "ftp", "webdav", "webdavs", "http", "https",
)

/**
 * Keeps the most recently opened remote archive container (and its pinned header / central-directory
 * blocks + open transport handle) warm between [ArchiveRepositoryImpl.listEntries] and playback start,
 * as well as across back-to-back player seeks/probes.
 */
private object RemoteContainerPool {
    private const val IDLE_CLOSE_DELAY_MS = 90_000L
    private val lock = Any()
    private val reaper = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "RemoteArchiveReaper").apply { isDaemon = true }
    }

    private var cachedUri: String? = null
    private var cachedSource: CloseableRandomAccessSource? = null
    private var refCount = 0
    private var idleCloseFuture: ScheduledFuture<*>? = null

    fun acquire(containerUri: String, factory: () -> CloseableRandomAccessSource): CloseableRandomAccessSource {
        var toClose: CloseableRandomAccessSource? = null
        synchronized(lock) {
            idleCloseFuture?.cancel(false)
            idleCloseFuture = null
            val existing = cachedSource
            if (existing != null && cachedUri == containerUri) {
                refCount++
                return Lease(existing)
            }
            if (existing != null && refCount == 0) {
                toClose = existing
                cachedSource = null
                cachedUri = null
            }
        }
        runCatching { toClose?.close() }

        val created = factory()
        var orphanToClose: CloseableRandomAccessSource? = null
        val leased = synchronized(lock) {
            if (cachedSource == null) {
                cachedUri = containerUri
                cachedSource = created
                refCount = 1
                Lease(created)
            } else if (cachedUri == containerUri) {
                orphanToClose = created
                refCount++
                Lease(cachedSource!!)
            } else {
                // Another container is still actively leased; return a standalone wrapper.
                return created
            }
        }
        runCatching { orphanToClose?.close() }
        return leased
    }

    private fun release(source: CloseableRandomAccessSource) {
        synchronized(lock) {
            if (source !== cachedSource) {
                runCatching { source.close() }
                return
            }
            refCount = (refCount - 1).coerceAtLeast(0)
            if (refCount == 0) {
                idleCloseFuture?.cancel(false)
                idleCloseFuture = reaper.schedule({
                    var expired: CloseableRandomAccessSource? = null
                    synchronized(lock) {
                        if (refCount == 0 && cachedSource === source) {
                            expired = cachedSource
                            cachedSource = null
                            cachedUri = null
                        }
                    }
                    runCatching { expired?.close() }
                }, IDLE_CLOSE_DELAY_MS, TimeUnit.MILLISECONDS)
            }
        }
    }

    private class Lease(
        private val delegate: CloseableRandomAccessSource,
    ) : CloseableRandomAccessSource, RandomAccessMediaSource by delegate {
        private val closed = AtomicBoolean(false)
        override fun close() {
            if (closed.compareAndSet(false, true)) {
                release(delegate)
            }
        }
    }
}

/**
 * Opens (or reuses a warm) [CloseableRandomAccessSource] for any supported remote URI so native
 * libarchive can seek and read archive headers, central directories, and compressed entries over
 * the network with minimal latency.
 */
@Throws(IOException::class)
fun openRemoteRandomAccessSource(
    containerUri: String,
    lightweight: Boolean = false,
): CloseableRandomAccessSource = RemoteContainerPool.acquire(containerUri) {
    val uri = Uri.parse(containerUri)
    val scheme = uri.scheme?.lowercase()
        ?: throw IOException("Missing URI scheme in $containerUri")
    when (scheme) {
        "smb" -> openSmbSource(uri, containerUri)
        "sftp" -> openSftpSource(uri)
        "ftp" -> openFtpSource(uri)
        "webdav", "webdavs", "http", "https" -> openWebDavOrHttpSource(uri, scheme)
        else -> throw IOException("Unsupported remote archive scheme: $scheme")
    }
}

private fun openSmbSource(
    uri: Uri,
    rawUri: String,
): CloseableRandomAccessSource {
    val host = uri.host ?: throw IOException("SMB: missing host in $rawUri")
    val port = if (uri.port != -1) uri.port else 445
    val (user, pass) = uri.userInfoPair()
    val segments = SmbPathResolver.decodedSegmentsOf(uri.encodedPath)
    if (segments.size < 2) throw IOException("SMB: path lacks share/file in $rawUri")

    val lease = ConnectionPool.borrowSmbContextLease(host, port, user, pass)
    try {
        val file = SmbPathResolver.resolveStrict(lease.context, host, port, segments)
            ?: throw IOException("SMB: cannot resolve $rawUri")
        val fileSize = file.length()
        if (fileSize <= 0L) throw IOException("SMB: empty or unreadable file $rawUri")
        return SmbBlockRandomAccessSource(
            file = file,
            fileSize = fileSize,
            onRelease = { lease.release() },
        )
    } catch (t: Throwable) {
        lease.release()
        throw (t as? IOException ?: IOException("SMB open failed for $rawUri", t))
    }
}

private fun openSftpSource(uri: Uri): CloseableRandomAccessSource {
    val host = uri.host ?: throw IOException("SFTP: missing host")
    val port = uri.port.takeIf { it > 0 } ?: 22
    val (user, pass) = uri.userInfoPair()
    val path = uri.path ?: throw IOException("SFTP: missing path")

    val ssh = ConnectionPool.borrowSsh(host, port, user, pass)
    var sftp: SFTPClient? = null
    var remoteFile: RemoteFile? = null
    try {
        sftp = ssh.newSFTPClient() ?: throw IOException("SFTP: failed to create SFTP client")
        remoteFile = sftp.open(path)
        val fileSize = remoteFile.length()
        if (fileSize <= 0L) throw IOException("SFTP: empty or unreadable file $path")
        return SftpBlockRandomAccessSource(
            sftpClient = sftp,
            remoteFile = remoteFile,
            fileSize = fileSize,
            onRelease = { ConnectionPool.returnSsh(host, port, user, pass) },
        )
    } catch (t: Throwable) {
        runCatching { remoteFile?.close() }
        runCatching { sftp?.close() }
        ConnectionPool.returnSsh(host, port, user, pass)
        throw (t as? IOException ?: IOException("SFTP open failed for $path", t))
    }
}

private fun openFtpSource(uri: Uri): CloseableRandomAccessSource {
    val host = uri.host ?: throw IOException("FTP: missing host")
    val port = uri.port.takeIf { it > 0 } ?: 21
    val (user, pass) = uri.userInfoPair()
    val path = uri.path ?: throw IOException("FTP: missing path")

    val ftp = ConnectionPool.borrowFtp(host, port, user, pass)
    try {
        val fileSize = queryFtpFileSize(ftp, path)
        if (fileSize <= 0L) throw IOException("FTP: cannot determine size of $path")
        return FtpBlockRandomAccessSource(
            ftp = ftp,
            path = path,
            fileSize = fileSize,
            onRelease = { ConnectionPool.returnFtp(host, port, user, pass) },
        )
    } catch (t: Throwable) {
        ConnectionPool.returnFtp(host, port, user, pass)
        throw (t as? IOException ?: IOException("FTP open failed for $path", t))
    }
}

private fun queryFtpFileSize(ftp: FTPClient, path: String): Long {
    runCatching { ftp.mlistFile(path)?.size?.takeIf { it > 0L } }
        .getOrNull()?.let { return it }
    runCatching {
        if (ftp.sendCommand("SIZE", path) == 213) {
            ftp.replyString?.trim()?.substringAfter(' ')?.trim()?.toLongOrNull()?.takeIf { it > 0L }
        } else null
    }.getOrNull()?.let { return it }
    return runCatching { ftp.listFiles(path)?.firstOrNull()?.size ?: -1L }.getOrDefault(-1L)
}

private fun openWebDavOrHttpSource(uri: Uri, scheme: String): CloseableRandomAccessSource {
    val isTls = scheme == "webdavs" || scheme == "https"
    val host = uri.host ?: throw IOException("WebDAV/HTTP: missing host")
    val port = uri.port.takeIf { it > 0 } ?: if (isTls) 443 else 80
    val (user, pass) = uri.userInfoPair()
    val httpScheme = if (isTls) "https" else "http"
    val httpUrl = HttpUrl.Builder()
        .scheme(httpScheme)
        .host(host.removeSurrounding("[", "]"))
        .port(port)
        .encodedPath(uri.encodedPath ?: "/")
        .query(uri.encodedQuery)
        .build()

    val headers = buildMap {
        if (user.isNotEmpty() && isTls) {
            put("Authorization", Credentials.basic(user, pass))
        }
    }
    val client = ConnectionPool.borrowWebDavClient(host, port, isTls, user, pass)
    val httpSource = HttpRandomAccessSource(
        url = httpUrl.toString(),
        headers = headers,
        client = client,
    )
    if (!httpSource.prepare()) {
        httpSource.close()
        ConnectionPool.returnWebDavClient(host, port, isTls, user, pass)
        throw IOException("WebDAV/HTTP: failed to prepare random access source for ${httpUrl.encodedPath}")
    }
    return HttpBlockRandomAccessSource(
        delegate = httpSource,
        onRelease = { ConnectionPool.returnWebDavClient(host, port, isTls, user, pass) },
    )
}

/**
 * Sliding-window block cache (128 KB blocks, pinned 1 MB head & 1 MB tail + 6 MB LRU window)
 * with adaptive sequential prefetch:
 * - Random probes (e.g. header → central directory at EOF) fetch ONLY the single 128 KB block
 *   needed, and keep both head and tail blocks permanently pinned in memory.
 * - Sequential reads (`blockIdx == lastBlockIdx + 1`) trigger background read-ahead of the next
 *   2 blocks when the subclass supports concurrent prefetch (`canPrefetchConcurrently = true`).
 */
private abstract class BlockCachedRandomAccessSource(
    private val fileSize: Long,
    private val canPrefetchConcurrently: Boolean = false,
) : CloseableRandomAccessSource {

    protected val lock = Any()
    @Volatile protected var closed = false

    protected class CachedBlock(val data: ByteArray, val length: Int)

    private val totalBlocks = (fileSize + BLOCK_SIZE - 1) / BLOCK_SIZE
    private val pinnedBlocks = HashMap<Long, CachedBlock>()
    private val blockCache = object : LinkedHashMap<Long, CachedBlock>(MAX_LRU_BLOCKS, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<Long, CachedBlock>?): Boolean =
            size > MAX_LRU_BLOCKS
    }
    private val inFlight = HashMap<Long, CompletableFuture<CachedBlock?>>()
    private var lastReadBlockIdx: Long = -2L
    private var sequentialStreak: Int = 0

    override fun getSize(): Long = fileSize

    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        if (closed) throw IOException("Source is closed")
        if (position < 0 || position >= fileSize) return -1
        val want = size.toLong().coerceAtMost(fileSize - position).toInt()
        if (want <= 0) return 0

        var copied = 0
        var cur = position
        while (copied < want) {
            val blockIdx = cur / BLOCK_SIZE
            val offsetInBlock = (cur - blockIdx * BLOCK_SIZE).toInt()
            val block = getOrLoadBlock(blockIdx) ?: break
            if (offsetInBlock >= block.length) break
            val avail = block.length - offsetInBlock
            val chunk = (want - copied).coerceAtMost(avail)
            System.arraycopy(block.data, offsetInBlock, buffer, copied, chunk)
            copied += chunk
            cur += chunk
            if (block.length < BLOCK_SIZE && chunk == avail) break
        }
        return if (copied == 0) -1 else copied
    }

    private fun getOrLoadBlock(blockIdx: Long): CachedBlock? {
        var futureToWait: CompletableFuture<CachedBlock?>? = null
        var shouldPrefetch = false
        synchronized(lock) {
            if (closed) return null
            if (blockIdx != lastReadBlockIdx) {
                if (blockIdx == lastReadBlockIdx + 1) {
                    sequentialStreak++
                } else {
                    sequentialStreak = 0
                }
                lastReadBlockIdx = blockIdx
                shouldPrefetch = canPrefetchConcurrently && sequentialStreak >= 1
            }

            pinnedBlocks[blockIdx]?.let {
                if (shouldPrefetch) schedulePrefetchLocked(blockIdx)
                return it
            }
            blockCache[blockIdx]?.let {
                if (shouldPrefetch) schedulePrefetchLocked(blockIdx)
                return it
            }

            futureToWait = inFlight[blockIdx]
            if (futureToWait == null) {
                if (canPrefetchConcurrently) {
                    val created = CompletableFuture<CachedBlock?>()
                    inFlight[blockIdx] = created
                    futureToWait = created
                    if (shouldPrefetch) schedulePrefetchLocked(blockIdx)
                } else {
                    val loaded = loadSingleBlockSynchronously(blockIdx)
                    if (loaded != null) storeBlockLocked(blockIdx, loaded)
                    return loaded
                }
            }
        }

        val fut = futureToWait!!
        if (!fut.isDone) {
            try {
                val loaded = loadSingleBlockSynchronously(blockIdx)
                synchronized(lock) {
                    if (loaded != null && !closed) storeBlockLocked(blockIdx, loaded)
                    inFlight.remove(blockIdx)
                }
                fut.complete(loaded)
                return loaded
            } catch (t: Throwable) {
                synchronized(lock) { inFlight.remove(blockIdx) }
                fut.completeExceptionally(t)
                throw (t as? IOException ?: IOException(t))
            }
        }
        return try {
            fut.get()
        } catch (t: Throwable) {
            throw (t.cause as? IOException ?: IOException(t))
        }
    }

    private fun schedulePrefetchLocked(currentBlockIdx: Long) {
        for (step in 1..PREFETCH_AHEAD_BLOCKS) {
            val nextIdx = currentBlockIdx + step
            if (nextIdx >= totalBlocks) break
            if (pinnedBlocks.containsKey(nextIdx) || blockCache.containsKey(nextIdx) || inFlight.containsKey(nextIdx)) {
                continue
            }
            val fut = CompletableFuture<CachedBlock?>()
            inFlight[nextIdx] = fut
            Dispatchers.IO.asExecutor().execute {
                try {
                    val block = loadPrefetchBlock(nextIdx)
                    synchronized(lock) {
                        if (block != null && !closed) storeBlockLocked(nextIdx, block)
                        inFlight.remove(nextIdx)
                    }
                    fut.complete(block)
                } catch (t: Throwable) {
                    synchronized(lock) { inFlight.remove(nextIdx) }
                    fut.complete(null)
                }
            }
        }
    }

    private fun storeBlockLocked(blockIdx: Long, block: CachedBlock) {
        val isHead = blockIdx < PINNED_HEAD_BLOCKS
        val isTail = totalBlocks > 0 && blockIdx >= (totalBlocks - PINNED_TAIL_BLOCKS)
        if (isHead || isTail) {
            pinnedBlocks[blockIdx] = block
        } else {
            blockCache[blockIdx] = block
        }
    }

    private fun loadSingleBlockSynchronously(blockIdx: Long): CachedBlock? {
        val blockOffset = blockIdx * BLOCK_SIZE
        val expected = BLOCK_SIZE.toLong().coerceAtMost(fileSize - blockOffset).toInt()
        if (expected <= 0) return null
        val buf = ByteArray(expected)
        val got = fetchBlock(blockOffset, buf, expected, isPrefetch = false)
        if (got > 0 && this !is HttpBlockRandomAccessSource) {
            com.rhnxdev.hzplayer.domain.model.SessionNetworkTracker.onBytesRead(got.toLong())
        }
        return if (got > 0) CachedBlock(buf, got) else null
    }

    private fun loadPrefetchBlock(blockIdx: Long): CachedBlock? {
        if (closed) return null
        val blockOffset = blockIdx * BLOCK_SIZE
        val expected = BLOCK_SIZE.toLong().coerceAtMost(fileSize - blockOffset).toInt()
        if (expected <= 0) return null
        val buf = ByteArray(expected)
        val got = fetchBlock(blockOffset, buf, expected, isPrefetch = true)
        if (got > 0 && this !is HttpBlockRandomAccessSource) {
            com.rhnxdev.hzplayer.domain.model.SessionNetworkTracker.onBytesRead(got.toLong())
        }
        return if (got > 0) CachedBlock(buf, got) else null
    }

    @Throws(IOException::class)
    protected abstract fun fetchBlock(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        isPrefetch: Boolean,
    ): Int

    protected fun clearCachesLocked() {
        pinnedBlocks.clear()
        blockCache.clear()
        inFlight.values.forEach { it.cancel(true) }
        inFlight.clear()
    }

    companion object {
        const val BLOCK_SIZE = 128 * 1024          // 128 KB blocks for low initial probe latency
        private const val MAX_LRU_BLOCKS = 48      // 6 MB sliding-window LRU cache
        private const val PINNED_HEAD_BLOCKS = 8   // 1 MB permanently pinned header
        private const val PINNED_TAIL_BLOCKS = 8   // 1 MB permanently pinned central directory / trailer
        private const val PREFETCH_AHEAD_BLOCKS = 2
    }
}

/**
 * SMB random-access source tailored for archives: opens a primary [SmbRandomAccessFile] handle
 * immediately (1 round-trip instead of 3) and lazily opens a second handle only when sequential
 * streaming prefetch kicks in.
 */
private class SmbBlockRandomAccessSource(
    private val file: SmbFile,
    fileSize: Long,
    private val onRelease: () -> Unit,
) : BlockCachedRandomAccessSource(fileSize, canPrefetchConcurrently = true) {

    private val primaryLock = Any()
    private val prefetchLock = Any()
    private val primaryHandle = SmbRandomAccessFile(file, "r")
    @Volatile private var prefetchHandle: SmbRandomAccessFile? = null
    private val activeOps = AtomicInteger(0)
    private val released = AtomicBoolean(false)

    override fun fetchBlock(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        isPrefetch: Boolean,
    ): Int {
        if (closed) return -1
        activeOps.incrementAndGet()
        try {
            return if (isPrefetch) {
                synchronized(prefetchLock) {
                    if (closed) return -1
                    val h = prefetchHandle ?: SmbRandomAccessFile(file, "r").also { prefetchHandle = it }
                    readFromHandle(h, offset, buffer, length)
                }
            } else {
                synchronized(primaryLock) {
                    if (closed) return -1
                    readFromHandle(primaryHandle, offset, buffer, length)
                }
            }
        } finally {
            if (activeOps.decrementAndGet() == 0 && closed) {
                closeHandlesAndRelease()
            }
        }
    }

    private fun readFromHandle(
        handle: SmbRandomAccessFile,
        offset: Long,
        buffer: ByteArray,
        length: Int,
    ): Int {
        handle.seek(offset)
        var total = 0
        while (total < length && !closed) {
            val n = handle.read(buffer, total, length - total)
            if (n <= 0) break
            total += n
        }
        return total
    }

    private fun closeHandlesAndRelease() {
        if (released.compareAndSet(false, true)) {
            runCatching { primaryHandle.close() }
            runCatching { prefetchHandle?.close() }
            prefetchHandle = null
            onRelease()
        }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            clearCachesLocked()
        }
        if (activeOps.get() == 0) {
            closeHandlesAndRelease()
        }
    }
}

private class SftpBlockRandomAccessSource(
    private val sftpClient: SFTPClient,
    private val remoteFile: RemoteFile,
    fileSize: Long,
    private val onRelease: () -> Unit,
) : BlockCachedRandomAccessSource(fileSize, canPrefetchConcurrently = false) {

    private val ioLock = Any()
    private val released = AtomicBoolean(false)

    override fun fetchBlock(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        isPrefetch: Boolean,
    ): Int = synchronized(ioLock) {
        var total = 0
        while (total < length && !closed) {
            val n = remoteFile.read(offset + total, buffer, total, length - total)
            if (n <= 0) break
            total += n
        }
        total
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            clearCachesLocked()
        }
        synchronized(ioLock) {
            runCatching { remoteFile.close() }
            runCatching { sftpClient.close() }
        }
        if (released.compareAndSet(false, true)) onRelease()
    }
}

private class FtpBlockRandomAccessSource(
    private val ftp: FTPClient,
    private val path: String,
    fileSize: Long,
    private val onRelease: () -> Unit,
) : BlockCachedRandomAccessSource(fileSize, canPrefetchConcurrently = false) {

    private val ioLock = Any()
    private val released = AtomicBoolean(false)
    private var activeStream: InputStream? = null
    private var streamPos: Long = -1L

    override fun fetchBlock(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        isPrefetch: Boolean,
    ): Int = synchronized(ioLock) {
        if (activeStream == null || streamPos != offset) {
            closeActiveStreamLocked()
            ftp.setRestartOffset(offset)
            activeStream = ftp.retrieveFileStream(path)
                ?: throw IOException("FTP: retrieveFileStream failed at offset $offset")
            streamPos = offset
        }
        val stream = activeStream!!
        var total = 0
        try {
            while (total < length && !closed) {
                val n = stream.read(buffer, total, length - total)
                if (n <= 0) break
                total += n
                streamPos += n
            }
        } catch (e: IOException) {
            closeActiveStreamLocked()
            throw e
        }
        total
    }

    private fun closeActiveStreamLocked() {
        val s = activeStream ?: return
        activeStream = null
        streamPos = -1L
        runCatching { s.close() }
        runCatching { ftp.completePendingCommand() }
    }

    override fun close() {
        synchronized(lock) {
            closed = true
            clearCachesLocked()
        }
        synchronized(ioLock) {
            closeActiveStreamLocked()
        }
        if (released.compareAndSet(false, true)) onRelease()
    }
}

private class HttpBlockRandomAccessSource(
    private val delegate: HttpRandomAccessSource,
    private val onRelease: () -> Unit,
) : BlockCachedRandomAccessSource(delegate.getSize(), canPrefetchConcurrently = false) {

    private val ioLock = Any()
    private val released = AtomicBoolean(false)

    override fun fetchBlock(
        offset: Long,
        buffer: ByteArray,
        length: Int,
        isPrefetch: Boolean,
    ): Int = synchronized(ioLock) {
        var total = 0
        var scratch: ByteArray? = null
        while (total < length && !closed) {
            val want = length - total
            if (total == 0) {
                val n = delegate.readAt(offset, buffer, want)
                if (n <= 0) break
                total += n
            } else {
                val tmp = scratch ?: ByteArray(want).also { scratch = it }
                val n = delegate.readAt(offset + total, tmp, want)
                if (n <= 0) break
                System.arraycopy(tmp, 0, buffer, total, n)
                total += n
            }
        }
        total
    }

    override fun abortRead() = delegate.abortRead()

    override fun close() {
        synchronized(lock) {
            closed = true
            clearCachesLocked()
        }
        delegate.close()
        if (released.compareAndSet(false, true)) onRelease()
    }
}

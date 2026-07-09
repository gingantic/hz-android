package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.smb.SmbFile
import java.io.BufferedInputStream
import java.io.EOFException
import java.io.IOException
import java.io.InputStream

/**
 * A Media3 [DataSource] for SMB shares via jcifs-ng.
 *
 * Uses [SmbFile.getInputStream] wrapped in [BufferedInputStream] with a
 * 512 KB buffer for read-ahead. ExoPlayer handles seeks by close+reopen
 * with a new [DataSpec.position]; small backwards seeks use skip+discard.
 *
 * Shares one [jcifs.CIFSContext] per server via [ConnectionPool] so
 * concurrent DataSources (video + subs) stay within connection limits.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var closed = false

    /** URI with user-info stripped — safe for logs and thrown error messages. */
    private fun safeUri(u: Uri): String {
        val s = u.toString()
        val at = s.indexOf('@')
        if (at < 0) return s
        val schemeEnd = s.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        return s.substring(0, start) + s.substring(at + 1)
    }

    override fun open(dataSpec: DataSpec): Long {
        val openStart = SystemClock.elapsedRealtime()
        uri = dataSpec.uri
        closed = false
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: uri=${safeUri(dataSpec.uri)} pos=${dataSpec.position} len=${dataSpec.length}")

        val uriStr = dataSpec.uri
        val userInfo = uriStr.userInfo ?: ""
        val username = Uri.decode(userInfo.substringBefore(':'))
        val password = Uri.decode(userInfo.substringAfter(':', ""))
        val host = uriStr.host ?: throw IOException("No host in URI: ${safeUri(dataSpec.uri)}")
        val port = uriStr.port.takeIf { it > 0 } ?: 445

        val cifsCtx = ConnectionPool.borrowSmbContext(host, port, username, password)

        // Resolve the target by walking the directory tree via listFiles() rather
        // than constructing an SmbFile from a URL containing the path. jcifs
        // mis-handles %-encoded segments (spaces → "file not found", emoji /
        // fullwidth CJK → STATUS_OBJECT_NAME_INVALID). See [SmbPathResolver].
        //
        // Cache the resolved SmbFile per-URI: ExoPlayer seeks via close()+open()
        // with the same URI, and re-walking on every seek is wasteful. SmbFile is
        // bound to the pooled (long-lived) CIFSContext, so reuse across opens is safe.
        val cacheKey = dataSpec.uri.toString()
        val segments = SmbPathResolver.decodedSegmentsOf(uriStr.encodedPath)
        if (segments.isEmpty()) throw IOException("No path in URI: ${safeUri(dataSpec.uri)}")

        // Open with brief retry/backoff for transient network drops (e.g. Wi-Fi
        // handoff). Connection-stage only — mid-read errors are not retried.
        val (_, fileLength, rawStream) = openWithRetry(
            cifsCtx, host, port, segments, cacheKey, dataSpec
        )

        // BufferedInputStream does large SMB reads (512 KB at a time),
        // serving small ExoPlayer reads from memory. This is the main
        // throughput enabler — without it each ExoPlayer read is a SMB
        // round-trip, capping at ~45 KB/s regardless of server speed.
        inputStream = BufferedInputStream(rawStream, 512 * 1024)

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            else -> fileLength - dataSpec.position
        }

        val elapsed = SystemClock.elapsedRealtime() - openStart
        android.util.Log.d(TAG, "open OK: fileLength=$fileLength remaining=$bytesRemaining in ${elapsed}ms")
        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L || closed) return C.RESULT_END_OF_INPUT
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
            else length.toLong().coerceAtMost(bytesRemaining).toInt()
        val bytesRead = stream.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        closed = true
        val stream = inputStream
        inputStream = null
        uri = null
        try { stream?.close() } catch (_: Exception) {}
        transferEnded()
    }

    /** Seek forward via InputStream.skip(), falling back to read-1-byte if skip returns 0. */
    private fun skipFully(stream: InputStream, bytes: Long) {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // Some InputStreams return 0 from skip; fallback to read-1
            if (stream.read() == -1) throw EOFException("Unexpected EOF during seek")
            remaining--
        }
    }

    /**
     * Resolve + open the SMB file with transient-failure retry/backoff.
     * Retries connection-stage [IOException]s (network blips) up to 3 times
     * with 250/750/2000 ms backoff. Non-transient errors (file-not-found,
     * position-exceeds-length) are thrown immediately without retry.
     */
    private fun openWithRetry(
        cifsCtx: jcifs.CIFSContext,
        host: String,
        port: Int,
        segments: List<String>,
        cacheKey: String,
        dataSpec: DataSpec,
    ): Triple<SmbFile, Long, InputStream> {
        val backoffMs = longArrayOf(250, 750, 2000)
        var lastErr: IOException? = null
        repeat(backoffMs.size + 1) { attempt ->
            try {
                val file = resolvedFileCache[cacheKey] ?: run {
                    val match = SmbPathResolver.resolve(cifsCtx, host, port, segments)
                        ?: throw IOException("File not found: ${safeUri(dataSpec.uri)}")
                    resolvedFileCache[cacheKey] = match
                    match
                }
                val len = file.length()
                if (dataSpec.position > len) {
                    throw IOException("Position ${dataSpec.position} exceeds file length $len")
                }
                val s = file.inputStream
                if (dataSpec.position > 0) skipFully(s, dataSpec.position)
                return Triple(file, len, s)
            } catch (e: IOException) {
                lastErr = e
                if (e.message?.contains("File not found") == true) throw e
                if (e.message?.contains("exceeds file length") == true) throw e
                // Drop a possibly-stale cache entry so the next attempt re-resolves.
                resolvedFileCache.remove(cacheKey)
                if (attempt < backoffMs.size) {
                    android.util.Log.w(TAG, "SMB open attempt $attempt failed, retrying", e)
                    Thread.sleep(backoffMs[attempt])
                }
            }
        }
        throw lastErr ?: IOException("SMB open failed: ${safeUri(dataSpec.uri)}")
    }



    companion object {
        private const val TAG = "SmbDataSource"

        /**
         * URI → resolved [SmbFile], populated by directory-listing resolution.
         * Lets seek-driven reopens skip re-listing the parent directory.
         * Bounded by [MAX_CACHE] with simple FIFO eviction — entries are cheap
         * (SmbFile holds no socket) and tied to a pooled CIFSContext.
         */
        private const val MAX_CACHE = 32
        private val resolvedFileCache =
            object : java.util.LinkedHashMap<String, SmbFile>(16, 0.75f, true) {
                override fun removeEldestEntry(eldest: Map.Entry<String, SmbFile>) = size > MAX_CACHE
            }.let { java.util.Collections.synchronizedMap(it) }

        /** Drop resolved-file entries so none outlive [ConnectionPool.releaseAll]. */
        fun clearResolvedFileCache() = resolvedFileCache.clear()
    }
}

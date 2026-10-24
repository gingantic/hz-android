package com.rhnxdev.hzplayer.core.io

import android.util.Log
import com.rhnxdev.hzplayer.domain.player.NativeErrorClass
import okhttp3.Call
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okio.BufferedSource
import java.io.Closeable
import java.io.EOFException
import java.io.IOException
import java.net.SocketTimeoutException
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.withLock

/**
 * True when [uri] looks like a single progressive file rather than an HLS/DASH manifest.
 *
 * Only a hint: [HttpRandomAccessSource.prepare] re-checks the response and declines
 * playlists that hide behind a plain URL.
 *
 * @param uri — http(s) media URL; only the path before `?`/`#` is inspected
 * @param mimeType — optional MIME hint from the caller
 */
fun isProgressiveHttp(uri: String, mimeType: String?): Boolean {
    val path = uri.substringBefore('#').substringBefore('?').lowercase()
    if (path.endsWith(".m3u8") || path.endsWith(".m3u") || path.endsWith(".mpd")) return false
    val mime = mimeType?.lowercase().orEmpty()
    return !(mime.contains("mpegurl") || mime.contains("dash") || mime.contains("vnd.apple"))
}

/**
 * Bridges native FFmpeg to a progressive http(s) file through OkHttp.
 *
 * FFmpeg's own HTTP client cannot recover when a server ignores `Range` (it rejects the 200
 * reply) and gives up on chunked bodies, so one dropped connection ends playback after a 15 s
 * spin. This source re-requests from the last good byte, and when the server has no Range
 * support it re-reads from 0 and skips ahead, as ExoPlayer's HTTP source does. It also uses
 * Android's TLS stack instead of the bundled mbedTLS.
 *
 * Needs a known length (the bridge contract has no unknown size): [prepare] returns false for
 * chunked streams, playlists and any probe failure, and the caller keeps FFmpeg's own HTTP.
 * Once playing, every failure is retried here: the JNI layer turns any exception into
 * `AVERROR_EXIT`, which ends playback at once. The final class is in [lastErrorClass].
 *
 * @param url — http(s) media URL
 * @param headers — already sanitized request headers (Referer, Cookie, User-Agent, ...)
 * @param client — HTTP client; the shared default suits playback
 * @param retryDelaysMs — wait before each retry of one read; its size is the retry budget
 */
class HttpRandomAccessSource(
    private val url: String,
    private val headers: Map<String, String>,
    private val client: OkHttpClient = sharedClient,
    private val retryDelaysMs: LongArray = DEFAULT_RETRY_DELAYS_MS,
) : RandomAccessMediaSource, Closeable {

    companion object {
        private const val TAG = "HttpRandomAccessSource"

        /** Forward gap that is cheaper to read through than to reconnect for (Range servers). */
        private const val SKIP_MAX_BYTES = 128L * 1024
        private const val SKIP_STEP_BYTES = 1L shl 20
        private val DEFAULT_RETRY_DELAYS_MS = longArrayOf(0, 500, 1_500, 3_000)
        private val CONTENT_RANGE = Regex("""bytes\s+(\d+)-(\d+)/(\d+)""", RegexOption.IGNORE_CASE)

        private val sharedClient: OkHttpClient by lazy {
            OkHttpClient.Builder()
                .connectTimeout(8, TimeUnit.SECONDS)
                .readTimeout(10, TimeUnit.SECONDS)
                .writeTimeout(10, TimeUnit.SECONDS)
                .followRedirects(true)
                .followSslRedirects(true) // same as ExoPlayer's allowCrossProtocolRedirects
                .build()
        }
    }

    /** A non-2xx reply. */
    class HttpStatusException(val code: Int) : IOException("HTTP $code")

    private val lock = ReentrantLock()
    @Volatile private var aborted = false
    @Volatile private var closed = false
    @Volatile private var activeCall: Call? = null
    @Volatile private var totalSize = -1L

    /** [NativeErrorClass] of the failure that ended playback, or NONE. */
    @Volatile var lastErrorClass: Int = NativeErrorClass.NONE
        private set

    // Guarded by [lock].
    private var rangeSupported = false
    private var response: Response? = null
    private var stream: BufferedSource? = null
    private var streamPos = 0L

    /**
     * Probes the URL: checks status, length, range support and that it is not a playlist.
     * Blocking; cancel with [abortRead] or [close].
     *
     * @return true when this source can serve the file; false means "use FFmpeg's HTTP"
     */
    fun prepare(): Boolean = lock.withLock {
        try {
            val accepted = try {
                connectAt(0, probe = true)
            } catch (e: IOException) {
                Log.w(TAG, "Probe failed (${e.javaClass.simpleName}); falling back to FFmpeg HTTP")
                false
            } catch (e: IllegalArgumentException) {
                false // malformed URL or header
            }
            if (!accepted) dropStream()
            accepted && !aborted
        } finally {
            if (closed) dropStream()
        }
    }

    override fun getSize(): Long = totalSize

    @Throws(IOException::class)
    override fun readAt(position: Long, buffer: ByteArray, size: Int): Int {
        checkActive()
        lock.withLock {
            try {
                return readLocked(position, buffer, size)
            } finally {
                if (closed) dropStream()
            }
        }
    }

    private fun readLocked(position: Long, buffer: ByteArray, size: Int): Int {
        val total = totalSize
        if (position >= total) return -1
        val want = size.toLong().coerceAtMost(total - position).toInt()
        if (want <= 0) return -1

        var failures = 0
        while (true) {
            checkActive()
            try {
                seekStream(position)
                val n = stream!!.read(buffer, 0, want)
                if (n > 0) {
                    streamPos += n
                    return n
                }
                // The length is known, so an early end of body is a dropped connection.
                throw EOFException("Body ended at $streamPos of $total")
            } catch (e: HttpStatusException) {
                dropStream()
                if (e.code == 416) return -1
                if (!isRetryable(e) || failures >= retryDelaysMs.size) throw fail(e)
                failures = backoff(failures)
            } catch (e: IOException) {
                dropStream()
                checkActive()
                if (failures >= retryDelaysMs.size) throw fail(e)
                failures = backoff(failures)
            }
        }
    }

    /** Leaves [stream] positioned exactly at [position], reconnecting or skipping as needed. */
    private fun seekStream(position: Long) {
        if (stream != null) {
            if (position == streamPos) return
            val gap = position - streamPos
            // Without Range support a forward seek can only be read through.
            if (gap > 0 && (!rangeSupported || gap <= SKIP_MAX_BYTES)) {
                skipForward(gap)
                return
            }
            dropStream()
        }
        connectAt(position, probe = false)
    }

    /**
     * Opens the file at [position] (from 0 when Range is unsupported, then skips ahead).
     *
     * @param probe — first request of [prepare]: decides range support and size, and may
     *   decline the source by returning false
     */
    private fun connectAt(position: Long, probe: Boolean): Boolean {
        dropStream()
        val resp = openRequest(position, withRange = probe || rangeSupported)
        when (resp.code) {
            206 -> {
                val range = parseContentRange(resp.header("Content-Range"))
                if (range == null || range.first != position) {
                    resp.close()
                    if (probe) return false
                    throw IOException("Unexpected Content-Range ${resp.header("Content-Range")}")
                }
                if (probe) {
                    if (range.second <= 0) { resp.close(); return false }
                    rangeSupported = true
                    totalSize = range.second
                }
                attach(resp, position)
            }
            200 -> {
                if (probe) {
                    val length = resp.body?.contentLength() ?: -1L
                    if (length <= 0) { resp.close(); return false }
                    rangeSupported = false
                    totalSize = length
                } else if (rangeSupported && position > 0) {
                    rangeSupported = false // the server stopped honouring Range
                }
                attach(resp, 0)
                if (!probe && position > 0) skipForward(position)
            }
            else -> {
                resp.close()
                if (probe) return false
                throw HttpStatusException(resp.code)
            }
        }
        return !probe || acceptProbeBody(resp)
    }

    /** Declines playlists, manifests and transfer-encoded bodies, which this source cannot serve. */
    private fun acceptProbeBody(resp: Response): Boolean {
        val type = resp.header("Content-Type")?.lowercase().orEmpty()
        if (type.contains("mpegurl") || type.contains("dash")) return false
        val encoding = resp.header("Content-Encoding")?.lowercase()
        if (!encoding.isNullOrEmpty() && encoding != "identity") return false
        val source = stream ?: return false
        return !(source.request(7) && source.peek().readUtf8(7) == "#EXTM3U")
    }

    private fun openRequest(from: Long, withRange: Boolean): Response {
        val builder = Request.Builder().url(url)
        headers.forEach { (name, value) ->
            try {
                builder.header(name, value)
            } catch (_: IllegalArgumentException) {
                Log.w(TAG, "Dropped unsendable header: $name") // e.g. non-ASCII cookie text
            }
        }
        // OkHttp would otherwise gunzip transparently and drop Content-Length.
        builder.header("Accept-Encoding", "identity")
        if (headers.keys.none { it.equals("Accept", ignoreCase = true) }) builder.header("Accept", "*/*")
        if (withRange) builder.header("Range", "bytes=$from-")
        val call = client.newCall(builder.build())
        activeCall = call
        if (aborted) call.cancel()
        return call.execute()
    }

    private fun attach(resp: Response, position: Long) {
        response = resp
        stream = resp.body!!.source()
        streamPos = position
    }

    private fun skipForward(bytes: Long) {
        val source = stream!!
        var remaining = bytes
        while (remaining > 0) {
            checkActive()
            val step = remaining.coerceAtMost(SKIP_STEP_BYTES)
            source.skip(step)
            streamPos += step
            remaining -= step
        }
    }

    private fun dropStream() {
        // Cancel first: closing a half-read HTTP/1.1 body would otherwise drain it.
        activeCall?.cancel()
        runCatching { response?.close() }
        response = null
        stream = null
        activeCall = null
    }

    private fun checkActive() {
        // Cancellation, not EOF: JniFile maps the exception to AVERROR_EXIT, so a native stop
        // does not look like the end of the file.
        if (aborted || closed) throw CancellationException("HTTP source aborted")
    }

    private fun isRetryable(e: HttpStatusException) = e.code == 408 || e.code == 429 || e.code in 500..599

    /** Waits before retry number [failures] and returns the new failure count. */
    private fun backoff(failures: Int): Int {
        var left = retryDelaysMs[failures]
        while (left > 0) {
            checkActive()
            val slice = left.coerceAtMost(50L)
            try {
                Thread.sleep(slice)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                throw CancellationException("HTTP source interrupted")
            }
            left -= slice
        }
        return failures + 1
    }

    private fun fail(e: IOException): IOException {
        lastErrorClass = when {
            e is HttpStatusException -> when (e.code) {
                401, 403 -> NativeErrorClass.AUTH
                404, 410 -> NativeErrorClass.NOT_FOUND
                else -> NativeErrorClass.SERVER
            }
            e is SocketTimeoutException -> NativeErrorClass.TIMEOUT
            else -> NativeErrorClass.NETWORK
        }
        Log.w(TAG, "Giving up: ${e.javaClass.simpleName} (class=$lastErrorClass)")
        return e
    }

    /** Parses `bytes <start>-<end>/<total>` into (start, total); null when malformed or total is `*`. */
    private fun parseContentRange(value: String?): Pair<Long, Long>? {
        val match = CONTENT_RANGE.find(value.orEmpty()) ?: return null
        return match.groupValues[1].toLong() to match.groupValues[3].toLong()
    }

    /** Cancels a blocked read at once; the source cannot be used afterwards. */
    override fun abortRead() {
        aborted = true
        activeCall?.cancel()
    }

    override fun close() {
        closed = true
        abortRead()
        // A reader that still holds the lock releases the stream itself (see readAt).
        if (lock.tryLock()) {
            try {
                dropStream()
            } finally {
                lock.unlock()
            }
        }
    }
}

package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import java.io.EOFException
import java.io.InputStream

/**
 * Shared state + read loop for the per-protocol remote [DataSource]s
 * (FTP, SFTP, SMB, WebDAV). Each subclass owns its [open]/[close] and the
 * transport-specific stream; the bounded read with [bytesRemaining] tracking
 * lives here so all four behave identically.
 *
 * Subclasses that need extra gating around the raw stream read (e.g. WebDAV's
 * error logging) override [readFromStream].
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
abstract class RemoteDataSourceBase(isNetwork: Boolean) : BaseDataSource(isNetwork) {

    protected var inputStream: InputStream? = null
    protected var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    protected var uriValue: Uri? = null

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
            else length.toLong().coerceAtMost(bytesRemaining).toInt()
        val bytesRead = readFromStream(stream, buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uriValue

    /** Raw single-buffer read. Override to add logging/guards around the stream. */
    protected open fun readFromStream(
        stream: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = stream.read(buffer, offset, length)

    /** Reset shared state; subclasses call this at the top of [close]. */
    protected fun resetSharedState() {
        uriValue = null
        inputStream = null
        bytesRemaining = C.LENGTH_UNSET.toLong()
    }

    /**
     * URI with user-info stripped — safe for logs and thrown error messages.
     * The [Uri.getUserInfo] guard matters: without it a credential-free URI
     * whose path contains `@` (e.g. `smb://host/movie@2x.mp4`) loses its host.
     */
    protected fun safeUri(uri: Uri): String {
        val raw = uri.toString()
        if (uri.userInfo == null) return raw
        val at = raw.indexOf('@')
        if (at < 0) return raw
        val schemeEnd = raw.indexOf("://")
        val start = if (schemeEnd >= 0) schemeEnd + 3 else 0
        return raw.substring(0, start) + raw.substring(at + 1)
    }

    /**
     * Bytes left to read for [dataSpec], preferring an explicit length and
     * otherwise the transport's total minus the requested offset.
     * [C.LENGTH_UNSET] for [totalLength] means the size is unknown.
     */
    protected fun resolveBytesRemaining(dataSpec: DataSpec, totalLength: Long): Long = when {
        dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
        totalLength != C.LENGTH_UNSET.toLong() -> totalLength - dataSpec.position
        else -> C.LENGTH_UNSET.toLong()
    }

    /**
     * Seek forward via [InputStream.skip], falling back to read-1-byte when skip returns 0.
     * [eofMessage] lets the caller name the failing stream in the EOF error.
     */
    protected fun skipFully(stream: InputStream, bytes: Long, eofMessage: String = "Unexpected EOF during seek") {
        var remaining = bytes
        while (remaining > 0) {
            val skipped = stream.skip(remaining)
            if (skipped > 0) {
                remaining -= skipped
                continue
            }
            // Some InputStreams return 0 from skip; fall back to reading a byte.
            if (stream.read() == -1) throw EOFException(eofMessage)
            remaining--
        }
    }

    companion object {
        private const val TAG = "RemoteDataSource"
    }
}

/** Backoff for connection-stage retries: one initial attempt plus 3 retries. */
internal val REMOTE_OPEN_BACKOFF_MS = longArrayOf(250, 750, 2000)

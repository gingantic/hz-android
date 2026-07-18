package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
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

    companion object {
        private const val TAG = "RemoteDataSource"
    }
}

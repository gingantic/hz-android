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

    override fun open(dataSpec: DataSpec): Long {
        val openStart = SystemClock.elapsedRealtime()
        uri = dataSpec.uri
        closed = false
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: uri=${dataSpec.uri} pos=${dataSpec.position} len=${dataSpec.length}")

        val uriStr = dataSpec.uri
        val userInfo = uriStr.userInfo ?: ""
        val username = Uri.decode(userInfo.substringBefore(':'))
        val password = Uri.decode(userInfo.substringAfter(':', ""))
        val host = uriStr.host ?: throw IOException("No host in URI: ${dataSpec.uri}")
        val port = uriStr.port.takeIf { it > 0 } ?: 445

        val cifsCtx = ConnectionPool.borrowSmbContext(host, port, username, password)
        val cleanUrl = stripCredentials(uriStr)
        android.util.Log.d(TAG, "cleanUrl=$cleanUrl")

        val file = SmbFile(cleanUrl, cifsCtx)

        // Cache file length — avoid extra SMB round-trip on reopen after seek
        val fileLength = file.length()
        if (dataSpec.position > fileLength) {
            throw IOException("Position ${dataSpec.position} exceeds file length $fileLength")
        }

        // Open SMB InputStream — skip forward if seeking
        val rawStream: InputStream = try {
            val s = file.inputStream
            if (dataSpec.position > 0) skipFully(s, dataSpec.position)
            s
        } catch (e: Exception) {
            throw IOException("Failed to open SMB file: ${e.message}", e)
        }

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

    /** Strip userinfo from URI — keeps decoded path as jcifs-ng expects. */
    private fun stripCredentials(uri: Uri): String {
        val host = uri.host ?: return uri.toString()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path ?: "/"
        return "smb://$host$port$path"
    }

    companion object {
        private const val TAG = "SmbDataSource"
    }
}

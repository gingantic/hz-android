package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A Media3 [DataSource] that reads from FTP shares via Apache Commons Net.
 *
 * Reuses a persistent control connection via [ConnectionPool] so that seek → open
 * doesn't re-login (eliminates the ~200ms handshake per seek).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FtpDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var client: FTPClient? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var ftpHost: String? = null
    private var ftpPort: Int = 21
    private var ftpUser: String? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: uri=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}")

        val userInfo = dataSpec.uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val user = Uri.decode(parts.getOrNull(0) ?: "anonymous")
        val pass = Uri.decode(parts.getOrNull(1) ?: "")
        val host = dataSpec.uri.host ?: throw IOException("No host in URI")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: 21
        val path = dataSpec.uri.path ?: "/"

        ftpHost = host
        ftpPort = port
        ftpUser = user

        // Borrow from pool — keeps control connection alive across seeks
        val ftp = ConnectionPool.borrowFtp(host, port, user, pass)

        val fileLength = try { ftp.mlistFile(path)?.size ?: C.LENGTH_UNSET.toLong() } catch (_: Exception) { C.LENGTH_UNSET.toLong() }

        // Use REST to seek server-side — no data wasted
        if (dataSpec.position > 0) {
            ftp.setRestartOffset(dataSpec.position)
        }
        val stream = ftp.retrieveFileStream(path) ?: throw IOException("Cannot retrieve file: $path")
        inputStream = BufferedInputStream(stream, 512 * 1024)
        client = ftp

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileLength != C.LENGTH_UNSET.toLong() -> fileLength - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
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
        uri = null
        val ftp = client
        try { inputStream?.close() } catch (_: Exception) {}
        inputStream = null
        // FTP leaves the control connection in a "transfer pending" state until
        // completePendingCommand() is called. Without it the pooled client is
        // half-dead for the next borrow (stuck mid-transfer) → seeks fail.
        try { ftp?.completePendingCommand() } catch (_: Exception) {}
        client = null // release reference — control connection stays pooled
        ftpHost?.let { host ->
            ConnectionPool.returnFtp(host, ftpPort, ftpUser ?: "")
        }
        transferEnded()
    }

    companion object {
        private const val TAG = "FtpDataSource"
    }
}

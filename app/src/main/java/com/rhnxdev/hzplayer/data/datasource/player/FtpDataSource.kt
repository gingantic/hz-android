package com.rhnxdev.hzplayer.data.datasource.player

import android.util.Log
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import com.rhnxdev.hzplayer.core.util.userInfoPair
import org.apache.commons.net.ftp.FTPClient
import java.io.BufferedInputStream
import java.io.IOException

/**
 * A Media3 [DataSource] that reads from FTP shares via Apache Commons Net.
 *
 * Reuses a persistent control connection via [ConnectionPool] so that seek → open
 * doesn't re-login (eliminates the ~200ms handshake per seek).
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class FtpDataSource : RemoteDataSourceBase(/* isNetwork = */ true) {

    private var client: FTPClient? = null
    private var ftpHost: String? = null
    private var ftpPort: Int = 21
    private var ftpUser: String? = null
    private var ftpPass: String? = null

    override fun open(dataSpec: DataSpec): Long {
        uriValue = dataSpec.uri
        transferInitializing(dataSpec)

        val (user, pass) = dataSpec.uri.userInfoPair()
        val host = dataSpec.uri.host ?: throw IOException("No host in URI")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: 21
        val path = dataSpec.uri.path ?: "/"

        ftpHost = host
        ftpPort = port
        ftpUser = user
        ftpPass = pass

        // Borrow from pool — keeps control connection alive across seeks
        val ftp = ConnectionPool.borrowFtp(host, port, user, pass)

        var fileLength = C.LENGTH_UNSET.toLong()
        try {
            fileLength = run {
                try { ftp.mlistFile(path)?.size ?: C.LENGTH_UNSET.toLong() }
                catch (_: Exception) { C.LENGTH_UNSET.toLong() }
            }

            // Use REST to seek server-side — no data wasted
            if (dataSpec.position > 0) {
                ftp.setRestartOffset(dataSpec.position)
            }
            val stream = ftp.retrieveFileStream(path) ?: throw IOException("Cannot retrieve file: $path")
            inputStream = BufferedInputStream(stream, 512 * 1024)
            client = ftp
        } catch (e: IOException) {
            Log.w(TAG, "open failed for ${safeUri(dataSpec.uri)}: ${e.message}")
            // open() threw after borrow but before close() runs → return the
            // connection so it isn't pinned inUse=1 for the process lifetime.
            ConnectionPool.returnFtp(host, port, user, pass)
            throw e
        }

        bytesRemaining = resolveBytesRemaining(dataSpec, fileLength)

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun close() {
        val ftp = client
        try { inputStream?.close() } catch (_: Exception) {}
        // FTP leaves the control connection in a "transfer pending" state until
        // completePendingCommand() is called. Without it the pooled client is
        // half-dead for the next borrow (stuck mid-transfer) → seeks fail.
        try { ftp?.completePendingCommand() } catch (_: Exception) {}
        client = null // release reference — control connection stays pooled
        resetSharedState()
        ftpHost?.let { host ->
            ConnectionPool.returnFtp(host, ftpPort, ftpUser ?: "", ftpPass ?: "")
        }
        transferEnded()
    }

    companion object {
        private const val TAG = "FtpDataSource"
    }
}

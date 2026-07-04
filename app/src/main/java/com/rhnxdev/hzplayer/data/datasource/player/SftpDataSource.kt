package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import net.schmizz.sshj.sftp.SFTPClient
import java.io.BufferedInputStream
import java.io.IOException
import java.io.InputStream

/**
 * A Media3 [DataSource] that reads from SFTP shares via SSHJ.
 *
 * Reuses the SSH connection across seeks via [ConnectionPool], eliminating
 * the ~500ms key-exchange handshake on every seek.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SftpDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var sshKey: String? = null
    private var sftpClient: SFTPClient? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: uri=${dataSpec.uri} position=${dataSpec.position} length=${dataSpec.length}")

        val userInfo = dataSpec.uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val user = Uri.decode(parts.getOrNull(0) ?: "anonymous")
        val pass = Uri.decode(parts.getOrNull(1) ?: "")
        val host = dataSpec.uri.host ?: throw IOException("No host in URI")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: 22
        val path = dataSpec.uri.path ?: "/"

        sshKey = "$host:$port:$user"

        val ssh = ConnectionPool.borrowSsh(host, port, user, pass)
        sftpClient = ssh.newSFTPClient()

        val sftpHandle = sftpClient!!.open(path)
        val fileLength = try { sftpHandle.length() } catch (_: Exception) { C.LENGTH_UNSET.toLong() }

        // Read via SSHJ's RemoteFile.read(position, buffer, offset, len).
        // Pass the full requested length — SSHJ handles internal chunking.
        var pos = dataSpec.position.coerceAtLeast(0)

        inputStream = BufferedInputStream(object : InputStream() {
            override fun read(): Int {
                val buf = ByteArray(1)
                return if (read(buf, 0, 1) == -1) -1 else buf[0].toInt() and 0xFF
            }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (pos >= fileLength && fileLength > 0) return -1
                val count = sftpHandle.read(pos, b, off, len)
                if (count < 0) return -1
                pos += count
                return count
            }
            override fun skip(n: Long): Long {
                pos = (pos + n).coerceAtMost(maxOf(fileLength, 0L))
                return n
            }
        }, 512 * 1024)

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
        try { inputStream?.close() } catch (_: Exception) {}
        try { sftpClient?.close() } catch (_: Exception) {}
        sftpClient = null
        transferEnded()
    }

    companion object {
        private const val TAG = "SftpDataSource"
    }
}

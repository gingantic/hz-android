package com.rhnxdev.hzplayer.data.datasource.network

import android.util.Log
import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.core.util.sortedRemote
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient

class SftpBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {
    private companion object { const val TAG = "SftpBrowserClient" }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect: host=$host port=$port")
        ConnectionPool.borrowSftpBrowser(host, port, username, password)
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "listDirectory: path=$path")
            val ssh = ConnectionPool.borrowSftpBrowser(host, port, username, password)
            ssh.newSFTPClient().use { sftp ->
                sftp.ls(path)
                    .filter { it.name != "." && it.name != ".." }
                    .map { entry ->
                    val filePath = if (path.endsWith("/")) "$path${entry.name}" else "$path/${entry.name}"
                    RemoteFileItem(
                        name = entry.name,
                        path = filePath,
                        isDirectory = entry.isDirectory,
                        fileSize = entry.attributes.size,
                        childCount = -1,
                        dateModified = entry.attributes.mtime * 1000L,
                        mimeType = if (!entry.isDirectory) guessMimeType(entry.name) else null,
                    )
                }
                .sortedRemote()
            }
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnSftpBrowser(host, port)
    }
}

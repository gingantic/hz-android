package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.core.util.guessMimeType
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

    override suspend fun connect() = withContext(Dispatchers.IO) {
        ConnectionPool.borrowSftpBrowser(host, port, username, password)
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val ssh = ConnectionPool.borrowSftpBrowser(host, port, username, password)
            val sftp = ssh.newSFTPClient()
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
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun countChildren(path: String): Int = withContext(Dispatchers.IO) {
        val ssh = ConnectionPool.borrowSftpBrowser(host, port, username, password)
        try {
            ssh.newSFTPClient().ls(path).count { it.isDirectory }
        } catch (_: Exception) { 0 }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnSftpBrowser(host, port)
    }
}

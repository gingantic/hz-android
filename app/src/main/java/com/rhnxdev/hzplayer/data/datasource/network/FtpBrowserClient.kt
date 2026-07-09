package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTPClient

class FtpBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    override suspend fun connect() = withContext(Dispatchers.IO) {
        ConnectionPool.borrowFtpBrowser(host, port, username, password)
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val ftp = ConnectionPool.borrowFtpBrowser(host, port, username, password)
            ftp.listFiles(path)
                .filter { it.name != "." && it.name != ".." }
                .map { file ->
                    val filePath = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}"
                    RemoteFileItem(
                        name = file.name,
                        path = filePath,
                        isDirectory = file.isDirectory,
                        fileSize = file.size,
                        childCount = -1,
                        dateModified = file.timestamp?.timeInMillis ?: 0,
                        mimeType = if (!file.isDirectory) guessMimeType(file.name) else null,
                    )
                }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun countChildren(path: String): Int = withContext(Dispatchers.IO) {
        val ftp = ConnectionPool.borrowFtpBrowser(host, port, username, password)
        try {
            ftp.listFiles(path).count { it.isDirectory }
        } catch (_: Exception) { 0 }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnFtpBrowser(host, port)
    }
}

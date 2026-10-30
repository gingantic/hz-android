package com.rhnxdev.hzplayer.data.datasource.network

import android.util.Log
import com.rhnxdev.hzplayer.core.util.remoteFileItem
import com.rhnxdev.hzplayer.core.util.sortedRemote
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException

class FtpBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {
    private companion object { const val TAG = "FtpBrowserClient" }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        Log.i(TAG, "connect: host=$host port=$port")
        try {
            ConnectionPool.borrowFtpBrowser(host, port, username, password)
        } catch (e: IOException) {
            ConnectionPool.dropFtpBrowser(host, port, username, password)
            throw e
        }
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            Log.d(TAG, "listDirectory: path=$path")
            try {
                val ftp = ConnectionPool.borrowFtpBrowser(host, port, username, password)
                ftp.listFiles(path)
                    .filter { it.name != "." && it.name != ".." }
                    .map { file ->
                        val filePath = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}"
                        remoteFileItem(
                            name = file.name,
                            path = filePath,
                            isDirectory = file.isDirectory,
                            fileSize = file.size,
                            dateModified = file.timestamp?.timeInMillis ?: 0,
                        )
                    }
                    .sortedRemote()
            } catch (e: IOException) {
                // A dead pooled client would otherwise be reused forever; see dropFtpBrowser.
                ConnectionPool.dropFtpBrowser(host, port, username, password)
                throw e
            }
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnFtpBrowser(host, port, username, password)
    }
}

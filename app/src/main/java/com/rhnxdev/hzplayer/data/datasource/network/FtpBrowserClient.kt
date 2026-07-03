package com.rhnxdev.hzplayer.data.datasource.network

import android.webkit.MimeTypeMap
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.apache.commons.net.ftp.FTP
import org.apache.commons.net.ftp.FTPClient
import java.net.URLConnection

class FtpBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    private var client: FTPClient? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ftp = FTPClient()
        ftp.connectTimeout = 10_000
        ftp.defaultTimeout = 10_000
        ftp.connect(host, port)
        ftp.login(username.ifEmpty { "anonymous" }, password.ifEmpty { "" })
        ftp.enterLocalPassiveMode()
        ftp.setFileType(FTP.BINARY_FILE_TYPE)
        client = ftp
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val ftp = client ?: throw IllegalStateException("Not connected")
            ftp.listFiles(path)
                .filter { it.name != "." && it.name != ".." }
                .map { file ->
                    val filePath = if (path.endsWith("/")) "$path${file.name}" else "$path/${file.name}"
                    RemoteFileItem(
                        name = file.name,
                        path = filePath,
                        isDirectory = file.isDirectory,
                        fileSize = file.size,
                        childCount = 0,
                        dateModified = file.timestamp?.timeInMillis ?: 0,
                        mimeType = if (!file.isDirectory) guessMimeType(file.name) else null,
                    )
                }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            client?.logout()
            client?.disconnect()
        } catch (_: Exception) {
        }
        client = null
    }

    private fun guessMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mime ?: URLConnection.guessContentTypeFromName(name)
    }
}

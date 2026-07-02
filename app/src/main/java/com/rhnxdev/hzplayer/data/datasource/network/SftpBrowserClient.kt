package com.rhnxdev.hzplayer.data.datasource.network

import android.webkit.MimeTypeMap
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import java.net.URLConnection

class SftpBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    private var sshClient: SSHClient? = null
    private var sftpClient: SFTPClient? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ssh = SSHClient()
        ssh.addHostKeyVerifier(PromiscuousVerifier())
        ssh.connectTimeout = 10_000
        ssh.connect(host, port)
        ssh.authPassword(username, password)
        sshClient = ssh
        sftpClient = ssh.newSFTPClient()
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val sftp = sftpClient ?: throw IllegalStateException("Not connected")
            sftp.ls(path)
                .filter { it.name != "." && it.name != ".." }
                .map { entry ->
                    val filePath = if (path.endsWith("/")) "$path${entry.name}" else "$path/${entry.name}"
                    RemoteFileItem(
                        name = entry.name,
                        path = filePath,
                        isDirectory = entry.isDirectory,
                        fileSize = entry.attributes.size,
                        dateModified = entry.attributes.mtime * 1000L,
                        mimeType = if (!entry.isDirectory) guessMimeType(entry.name) else null,
                    )
                }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        try {
            sftpClient?.close()
            sshClient?.disconnect()
        } catch (_: Exception) {
        }
        sftpClient = null
        sshClient = null
    }

    private fun guessMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mime ?: URLConnection.guessContentTypeFromName(name)
    }
}

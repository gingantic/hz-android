package com.rhnxdev.hzplayer.data.repository

import android.net.Uri
import com.rhnxdev.hzplayer.core.util.defaultPort
import com.rhnxdev.hzplayer.data.datasource.network.FtpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.RemoteBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SftpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SmbBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.WebDavBrowserClient
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class RemoteBrowseRepositoryImpl @Inject constructor() : RemoteBrowseRepository {

    override suspend fun listDirectory(
        server: ServerConfig,
        path: String,
    ): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        runCatching {
            val client = createClient(server)
            client.connect()
            client.listDirectory(path)
            // Keep connected — pooled connection reused for enrichDirectory
        }
    }

    override suspend fun enrichDirectory(
        server: ServerConfig,
        items: List<RemoteFileItem>,
    ): List<RemoteFileItem> = withContext(Dispatchers.IO) {
        val client = createClient(server)
        client.connect()
        items.map { item ->
            if (item.isDirectory) {
                val count = client.countChildren(item.path)
                item.copy(childCount = count)
            } else item
        }
    }

    override fun buildPlaybackUri(server: ServerConfig, remotePath: String): String {
        val credentials = if (server.username.isNotEmpty()) {
            val encodedUser = Uri.encode(server.username)
            val encodedPass = Uri.encode(server.password)
            "$encodedUser:$encodedPass@"
        } else ""
        val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        val portSuffix = if (server.port > 0) ":${server.port}" else ""
        val authority = "${credentials}${server.host}$portSuffix"
        val scheme = server.protocol.name.lowercase()
        val encodedPath = percentEncodePath(cleanPath)
        return "$scheme://$authority$encodedPath"
    }

    private fun percentEncodePath(path: String): String {
        val sb = StringBuilder(path.length + 32)
        for (ch in path) {
            when {
                ch == '/' -> sb.append(ch)
                ch in 'A'..'Z' || ch in 'a'..'z' || ch in '0'..'9' ||
                    ch == '-' || ch == '_' || ch == '.' || ch == '~' -> sb.append(ch)
                else -> {
                    val bytes = ch.toString().toByteArray(Charsets.UTF_8)
                    for (b in bytes) {
                        sb.append('%').append(String.format("%02X", b.toInt() and 0xFF))
                    }
                }
            }
        }
        return sb.toString()
    }

    private fun createClient(server: ServerConfig): RemoteBrowserClient = when (server.protocol) {
        NetworkProtocol.FTP -> FtpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SFTP -> SftpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SMB -> SmbBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.WEBDAV -> WebDavBrowserClient(server.host, server.port, server.username, server.password, useTls = false)
        NetworkProtocol.WEBDAVS -> WebDavBrowserClient(server.host, server.port, server.username, server.password, useTls = true)
    }
}

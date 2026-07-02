package com.rhnxdev.hzplayer.data.repository

import android.net.Uri
import com.rhnxdev.hzplayer.data.datasource.network.FtpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.RemoteBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SftpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SmbBrowserClient
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
            try {
                client.connect()
                client.listDirectory(path)
            } finally {
                client.disconnect()
            }
        }
    }

    override fun buildPlaybackUri(server: ServerConfig, remotePath: String): String {
        val credentials = if (server.username.isNotEmpty()) {
            val encodedUser = Uri.encode(server.username)
            val encodedPass = Uri.encode(server.password)
            "$encodedUser:$encodedPass@"
        } else {
            ""
        }
        val cleanPath = if (remotePath.startsWith("/")) remotePath else "/$remotePath"
        val portSuffix = if (server.port > 0) ":${server.port}" else ""
        return when (server.protocol) {
            NetworkProtocol.FTP -> "ftp://${credentials}${server.host}$portSuffix$cleanPath"
            NetworkProtocol.SFTP -> "sftp://${credentials}${server.host}$portSuffix$cleanPath"
            NetworkProtocol.SMB -> "smb://${credentials}${server.host}$portSuffix$cleanPath"
        }
    }

    private fun createClient(server: ServerConfig): RemoteBrowserClient = when (server.protocol) {
        NetworkProtocol.FTP -> FtpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SFTP -> SftpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SMB -> SmbBrowserClient(server.host, server.port, server.username, server.password)
    }
}

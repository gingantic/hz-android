package com.rhnxdev.hzplayer.data.repository

import android.net.Uri
import com.rhnxdev.hzplayer.core.util.defaultPort
import com.rhnxdev.hzplayer.data.datasource.network.FtpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.RemoteBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SftpBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.SmbBrowserClient
import com.rhnxdev.hzplayer.data.datasource.network.WebDavBrowserClient
import jcifs.smb.SmbAuthException
import com.rhnxdev.hzplayer.domain.model.FolderCounts
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.domain.repository.RemoteBrowseRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import javax.inject.Inject

class RemoteBrowseRepositoryImpl @Inject constructor() : RemoteBrowseRepository {

    companion object {
        /** Hard cap so a hung connection can't leave pull-to-refresh stuck forever. */
        private const val REMOTE_OP_TIMEOUT_MS = 30_000L
    }

    override suspend fun listDirectory(
        server: ServerConfig,
        path: String,
    ): Result<List<RemoteFileItem>> = withContext(Dispatchers.IO) {
        val client = createClient(server)
        val result = runCatching {
            withTimeout(REMOTE_OP_TIMEOUT_MS) {
                client.connect()
                client.listDirectory(path)
            }
        }
        result.onFailure { e ->
            if (e is SmbAuthException) {
                android.util.Log.w("RemoteBrowseRepo", "listDirectory failed (Access Denied) for protocol=${server.protocol} host=${server.host} path=$path: ${e.message}")
            } else {
                android.util.Log.e("RemoteBrowseRepo", "listDirectory failed for protocol=${server.protocol} host=${server.host} path=$path", e)
            }
        }
        // Always release the pooled connection so credentialed contexts don't
        // linger for the process lifetime.
        runCatching { client.disconnect() }
        result
    }

    override suspend fun enrichDirectory(
        server: ServerConfig,
        items: List<RemoteFileItem>,
        onCount: suspend (path: String, counts: FolderCounts) -> Unit,
    ) {
        withContext(Dispatchers.IO) {
            val client = createClient(server)
            runCatching {
                withTimeout(REMOTE_OP_TIMEOUT_MS) {
                    client.connect()
                    // One shared connection: list each folder's children once and classify
                    // them (subfolders / files / media) so the badge can show the breakdown.
                    // Report each folder's result as soon as it's ready.
                    items.forEach { item ->
                        if (item.isDirectory) {
                            val counts = runCatching {
                                val children = client.listDirectory(item.path)
                                val folders = children.count { it.isDirectory }
                                val files = children.size - folders
                                val media = children.count {
                                    !it.isDirectory &&
                                        (isVideoExtension(it.name) || isAudioExtension(it.name))
                                }
                                FolderCounts(folders = folders, files = files, media = media)
                            }.getOrDefault(FolderCounts())
                            onCount(item.path, counts)
                        }
                    }
                }
            }.also {
                runCatching { client.disconnect() }
            }
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
        return Uri.encode(path, "/")
    }

    private fun createClient(server: ServerConfig): RemoteBrowserClient = when (server.protocol) {
        NetworkProtocol.FTP -> FtpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SFTP -> SftpBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.SMB -> SmbBrowserClient(server.host, server.port, server.username, server.password)
        NetworkProtocol.WEBDAV -> WebDavBrowserClient(server.host, server.port, server.username, server.password, useTls = false)
        NetworkProtocol.WEBDAVS -> WebDavBrowserClient(server.host, server.port, server.username, server.password, useTls = true)
    }
}

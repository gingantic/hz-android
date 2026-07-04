package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    override suspend fun connect() = withContext(Dispatchers.IO) {
        ConnectionPool.borrowSmbBrowser(host, port, username, password)
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val ctx = ConnectionPool.borrowSmbBrowser(host, port, username, password)
            val smbPath = buildSmbPath(path)
            val smbDir = jcifs.smb.SmbFile(smbPath, ctx)
            val isRoot = path.replace("/", "").isEmpty()
            smbDir.listFiles()
                .filter { file ->
                    val cleanName = file.name.trimEnd('/')
                    val isHiddenShare = isRoot && cleanName.endsWith("$")
                    file.name != "." && file.name != ".." && !isHiddenShare
                }
                .map { file ->
                    val name = file.name.trimEnd('/')
                    val isDir = file.isDirectory
                    val filePath = if (path.endsWith("/")) "$path$name" else "$path/$name"
                    RemoteFileItem(
                        name = name,
                        path = filePath,
                        isDirectory = isDir,
                        fileSize = if (!isDir) file.length() else 0,
                        childCount = 0,
                        dateModified = file.lastModified(),
                        mimeType = if (!isDir) guessMimeType(name) else null,
                    )
                }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun countChildren(path: String): Int = withContext(Dispatchers.IO) {
        val ctx = ConnectionPool.borrowSmbBrowser(host, port, username, password)
        val smbPath = buildSmbPath(path)
        try {
            jcifs.smb.SmbFile(smbPath, ctx).listFiles()?.size ?: 0
        } catch (_: Exception) { 0 }
    }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnSmbBrowser(host, port, username)
    }

    private fun buildSmbPath(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val normalized = if (cleanPath.endsWith("/")) cleanPath else "$cleanPath/"
        val hostWithPort = if (port > 0 && port != 445) "$host:$port" else host
        return "smb://$hostWithPort$normalized"
    }
}

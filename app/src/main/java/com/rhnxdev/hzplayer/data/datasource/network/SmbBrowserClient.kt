package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.core.util.sortedRemote
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import jcifs.smb.SmbAuthException
import jcifs.smb.SmbFile
import java.util.ArrayDeque
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class SmbBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val ctx = ConnectionPool.borrowSmbBrowser(host, port, username, password)
        // Pre-flight: try to list the share root. If auth fails, evict from pool so
        // next attempt gets a fresh context instead of a cached bad one.
        try {
            SmbFile("smb://$host:${if (port > 0) port else 445}/", ctx).listFiles()
        } catch (_: SmbAuthException) {
            ConnectionPool.returnSmbBrowser(host, port, username, password)
            throw com.rhnxdev.hzplayer.domain.model.RemoteAuthException()
        }
        Unit
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val normalized = normalizeRemotePath(path)
            if (normalized == null) return@withContext emptyList()
            val ctx = ConnectionPool.borrowSmbBrowser(host, port, username, password)
            val isRoot = normalized.replace("/", "").isEmpty()
            val smbDir = resolveDir(ctx, normalized) ?: return@withContext emptyList()
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
                        childCount = -1,
                        dateModified = file.lastModified(),
                        mimeType = if (!isDir) guessMimeType(name) else null,
                    )
                }
                .sortedRemote()
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnSmbBrowser(host, port, username, password)
    }

    /**
     * Normalize a browsed [path] into canonical slash-separated segments, rejecting
     * traversal. Returns `null` if the path tries to escape the share root via ".."
     * (which could expose admin `$` shares), or `"/"` for the share root.
     */
    internal fun normalizeRemotePath(path: String): String? {
        val segments = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        val stack = ArrayDeque<String>()
        for (seg in segments) {
            when {
                seg == "." -> continue
                seg == ".." -> {
                    // Can't go above the share root — treat as a traversal attempt.
                    if (stack.isEmpty()) return null
                    stack.removeLast()
                }
                else -> stack.addLast(seg)
            }
        }
        return if (stack.isEmpty()) "/" else "/${stack.joinToString("/")}"
    }

    /**
     * Resolve a decoded directory [path] to an [SmbFile] by walking the tree via
     * [SmbPathResolver], so folder names containing spaces, emoji, or fullwidth
     * CJK punctuation resolve correctly (jcifs mishandles them in URLs). Browsed
     * paths carry literal decoded names, so they are passed through as-is.
     */
    private fun resolveDir(ctx: jcifs.CIFSContext, path: String): SmbFile? {
        val segments = path.removePrefix("/").split('/').filter { it.isNotEmpty() }
        val effectivePort = if (port > 0) port else 445
        return SmbPathResolver.resolve(ctx, host, effectivePort, segments)
    }
}

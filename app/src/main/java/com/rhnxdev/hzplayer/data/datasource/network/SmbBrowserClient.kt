package com.rhnxdev.hzplayer.data.datasource.network

import android.webkit.MimeTypeMap
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.URLConnection
import java.util.Properties

class SmbBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
) : RemoteBrowserClient {

    private var cifsContext: CIFSContext? = null

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val props = Properties()
        props.setProperty("jcifs.smb.client.minVersion", "SMB202")
        props.setProperty("jcifs.smb.client.maxVersion", "SMB311")
        props.setProperty("jcifs.smb.client.responseTimeout", "10000")
        props.setProperty("jcifs.smb.client.soTimeout", "10000")
        props.setProperty("jcifs.smb.client.dfs.disabled", "true")
        props.setProperty("jcifs.resolveOrder", "DNS")
        val baseContext = BaseContext(PropertyConfiguration(props))
        cifsContext = if (username.isNotEmpty()) {
            val auth = NtlmPasswordAuthenticator("", username, password)
            baseContext.withCredentials(auth)
        } else {
            baseContext.withGuestCrendentials()
        }
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val ctx = cifsContext ?: throw IllegalStateException("Not connected")
            val smbPath = buildSmbPath(path)
            val smbDir = SmbFile(smbPath, ctx)
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
                        childCount = if (isDir) {
                            try {
                                file.listFiles()?.size ?: 0
                            } catch (e: Exception) {
                                0
                            }
                        } else 0,
                        dateModified = file.lastModified(),
                        mimeType = if (!isDir) guessMimeType(name) else null,
                    )
                }
                .sortedWith(compareByDescending<RemoteFileItem> { it.isDirectory }.thenBy { it.name.lowercase() })
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        cifsContext = null
    }

    private fun buildSmbPath(path: String): String {
        val cleanPath = if (path.startsWith("/")) path else "/$path"
        val normalized = if (cleanPath.endsWith("/")) cleanPath else "$cleanPath/"
        val hostWithPort = if (port > 0 && port != 445) "$host:$port" else host
        return "smb://$hostWithPort$normalized"
    }

    private fun guessMimeType(name: String): String? {
        val ext = name.substringAfterLast('.', "").lowercase()
        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mime ?: URLConnection.guessContentTypeFromName(name)
    }
}

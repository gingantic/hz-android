package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import jcifs.CIFSContext
import jcifs.config.PropertyConfiguration
import jcifs.context.BaseContext
import jcifs.smb.NtlmPasswordAuthenticator
import jcifs.smb.SmbFile
import java.io.InputStream
import java.util.Properties

/**
 * A Media3 [DataSource] that reads from SMB shares via jcifs-ng.
 *
 * This allows ExoPlayer to play `smb://` URIs directly, including HDR/HDR10+
 * content with proper color metadata — something VLC's Android binding cannot
 * guarantee.
 *
 * Random-access (seeking) is supported via [DataSpec.position] so that
 * ExoPlayer can seek efficiently without re-reading from the start.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class SmbDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var smbFile: SmbFile? = null
    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)

        val cifsCtx = buildCifsContext(dataSpec.uri)
        // jcifs-ng requires the SMB URL to be credential-free (credentials in the context)
        val cleanUrl = stripCredentials(dataSpec.uri)

        val file = SmbFile(cleanUrl, cifsCtx)
        smbFile = file

        val fileLength = try { file.length() } catch (e: Exception) { C.LENGTH_UNSET.toLong() }

        val stream = file.openInputStream()
        // Skip to the requested position for random-access / seeking
        if (dataSpec.position > 0) {
            var skipped = 0L
            while (skipped < dataSpec.position) {
                val toSkip = (dataSpec.position - skipped).coerceAtMost(8192)
                val n = stream.skip(toSkip)
                if (n <= 0) break
                skipped += n
            }
        }
        inputStream = stream

        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            fileLength != C.LENGTH_UNSET.toLong() -> fileLength - dataSpec.position
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT

        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
            length
        } else {
            length.toLong().coerceAtMost(bytesRemaining).toInt()
        }

        val bytesRead = stream.read(buffer, offset, toRead)
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT

        if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
            bytesRemaining -= bytesRead
        }
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { inputStream?.close() } catch (_: Exception) {}
        try { smbFile?.close() } catch (_: Exception) {}
        inputStream = null
        smbFile = null
        transferEnded()
    }

    // ── Helpers ────────────────────────────────────────────────────────

    private fun buildCifsContext(uri: Uri): CIFSContext {
        val props = Properties().apply {
            setProperty("jcifs.smb.client.minVersion", "SMB202")
            setProperty("jcifs.smb.client.maxVersion", "SMB311")
            setProperty("jcifs.smb.client.responseTimeout", "15000")
            setProperty("jcifs.smb.client.soTimeout", "15000")
            setProperty("jcifs.smb.client.dfs.disabled", "true")
            setProperty("jcifs.resolveOrder", "DNS")
        }
        val base = BaseContext(PropertyConfiguration(props))
        val userInfo = uri.userInfo
        return if (!userInfo.isNullOrEmpty()) {
            val parts = userInfo.split(":", limit = 2)
            val user = Uri.decode(parts.getOrNull(0) ?: "")
            val pass = Uri.decode(parts.getOrNull(1) ?: "")
            val auth = NtlmPasswordAuthenticator("", user, pass)
            base.withCredentials(auth)
        } else {
            base.withGuestCrendentials()
        }
    }

    /** Remove user:pass@ from the URL so jcifs-ng doesn't double-parse it. */
    private fun stripCredentials(uri: Uri): String {
        val host = uri.host ?: return uri.toString()
        val port = if (uri.port > 0) ":${uri.port}" else ""
        val path = uri.path ?: "/"
        return "smb://$host$port$path"
    }
}

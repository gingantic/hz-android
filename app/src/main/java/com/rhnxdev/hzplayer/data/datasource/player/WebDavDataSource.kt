package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.BaseDataSource
import androidx.media3.datasource.DataSpec
import okhttp3.Request
import java.io.IOException
import java.io.InputStream

/**
 * A Media3 [DataSource] that reads from WebDAV shares via HTTP/HTTPS.
 *
 * Uses byte-range requests (`Range: bytes=…`) for server-side seek — no data
 * is wasted on skipped content. Underlying HTTP connections are pooled and
 * reused via [ConnectionPool].
 *
 * Accepts URI schemes:
 * - `webdav://host:port/path` → translated to `http://host:port/path`
 * - `webdavs://host:port/path` → translated to `https://host:port/path`
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class WebDavDataSource : BaseDataSource(/* isNetwork = */ true) {

    private var inputStream: InputStream? = null
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var response: okhttp3.Response? = null
    private var httpHost: String? = null
    private var httpPort: Int = 80
    private var useTls: Boolean = false

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: scheme=${dataSpec.uri.scheme} host=${dataSpec.uri.host} " +
            "path=${dataSpec.uri.path} position=${dataSpec.position} length=${dataSpec.length}")

        val scheme = dataSpec.uri.scheme?.lowercase() ?: throw IOException("No scheme in URI")
        val isTls = scheme == "webdavs"
        val userInfo = dataSpec.uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val user = Uri.decode(parts.getOrNull(0) ?: "")
        val pass = Uri.decode(parts.getOrNull(1) ?: "")
        val host = dataSpec.uri.host ?: throw IOException("No host in URI")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: if (isTls) 443 else 80
        val path = dataSpec.uri.path ?: "/"

        httpHost = host
        httpPort = port
        useTls = isTls

        // Build the real HTTP(S) URL
        val httpScheme = if (isTls) "https" else "http"
        val httpUrl = "$httpScheme://$host:$port$path"

        val okhttp = ConnectionPool.borrowWebDavClient(host, port, isTls, user, pass)

        val requestBuilder = Request.Builder().url(httpUrl)

        // Basic auth if credentials are present
        if (user.isNotEmpty()) {
            val credentials = okhttp3.Credentials.basic(user, pass)
            requestBuilder.header("Authorization", credentials)
        }

        // Range header for server-side seek
        if (dataSpec.position > 0) {
            requestBuilder.header("Range", "bytes=${dataSpec.position}-")
        }

        val httpResponse = okhttp.newCall(requestBuilder.build()).execute()
        response = httpResponse

        if (!httpResponse.isSuccessful) {
            httpResponse.close()
            throw IOException("WebDAV HTTP ${httpResponse.code} for $httpUrl")
        }

        val body = httpResponse.body ?: throw IOException("No response body from $httpUrl")
        inputStream = body.byteStream()

        // Determine content length
        val contentLength = body.contentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != -1L -> contentLength
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        android.util.Log.d(TAG, "open OK: remaining=$bytesRemaining")
        return bytesRemaining
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
        val stream = inputStream ?: return C.RESULT_END_OF_INPUT
        val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) length
            else length.toLong().coerceAtMost(bytesRemaining).toInt()
        val bytesRead = try {
            stream.read(buffer, offset, toRead)
        } catch (e: IOException) {
            android.util.Log.w(TAG, "read error", e)
            C.RESULT_END_OF_INPUT
        }
        if (bytesRead == -1) return C.RESULT_END_OF_INPUT
        if (bytesRemaining != C.LENGTH_UNSET.toLong()) bytesRemaining -= bytesRead
        bytesTransferred(bytesRead)
        return bytesRead
    }

    override fun getUri(): Uri? = uri

    override fun close() {
        uri = null
        try { inputStream?.close() } catch (_: Exception) {}
        inputStream = null
        try { response?.close() } catch (_: Exception) {}
        response = null
        httpHost?.let { host ->
            ConnectionPool.returnWebDavClient(host, httpPort, useTls)
        }
        transferEnded()
    }

    companion object {
        private const val TAG = "WebDavDataSource"
    }
}

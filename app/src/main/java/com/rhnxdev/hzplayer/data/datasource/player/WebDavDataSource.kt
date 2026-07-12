package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSpec
import okhttp3.HttpUrl
import okhttp3.Request
import java.io.BufferedInputStream
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
class WebDavDataSource : RemoteDataSourceBase(/* isNetwork = */ true) {

    private var response: okhttp3.Response? = null
    private var httpHost: String? = null
    private var httpPort: Int = 80
    private var useTls: Boolean = false
    private var webdavUser: String = ""
    private var webdavPass: String = ""

    override fun open(dataSpec: DataSpec): Long {
        uriValue = dataSpec.uri
        transferInitializing(dataSpec)
        android.util.Log.d(TAG, "open: scheme=${dataSpec.uri.scheme} host=${dataSpec.uri.host} " +
            "path=${dataSpec.uri.path} position=${dataSpec.position} length=${dataSpec.length}")

        val scheme = dataSpec.uri.scheme?.lowercase() ?: throw IOException("No scheme in URI")
        val isTls = scheme == "webdavs"
        val userInfo = dataSpec.uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val user = Uri.decode(parts.getOrNull(0) ?: "")
        val pass = Uri.decode(parts.getOrNull(1) ?: "")
        webdavUser = user
        webdavPass = pass
        val host = dataSpec.uri.host ?: throw IOException("No host in URI")
        val port = dataSpec.uri.port.takeIf { it > 0 } ?: if (isTls) 443 else 80
        val path = dataSpec.uri.encodedPath ?: "/"

        httpHost = host
        httpPort = port
        useTls = isTls

        // Build the real HTTP(S) URL. Use HttpUrl.Builder so IPv6 literals are
        // bracketed and the query is preserved (string concat dropped it).
        val httpScheme = if (isTls) "https" else "http"
        val androidUri = dataSpec.uri
        val httpUrl = HttpUrl.Builder()
            .scheme(httpScheme)
            .host(androidUri.host?.removeSurrounding("[", "]") ?: host)
            .port(port)
            .encodedPath(androidUri.encodedPath ?: path)
            .query(androidUri.encodedQuery)
            .build()
        // Credential-free URL for logs/errors (never leak user:pass).
        val safeUrl = httpUrl.newBuilder().username("").password("").build().toString()

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

        // Open with brief retry/backoff for transient network drops (Wi-Fi
        // handoff). Connection-stage only — HTTP status errors are not retried.
        val httpResponse = run {
            val backoffMs = longArrayOf(250, 750, 2000)
            var lastErr: IOException? = null
            repeat(backoffMs.size + 1) { attempt ->
                try {
                    val resp = okhttp.newCall(requestBuilder.build()).execute()
                    if (!resp.isSuccessful) {
                        resp.close()
                        throw IOException("WebDAV HTTP ${resp.code} for $safeUrl")
                    }
                    return@run resp
                } catch (e: IOException) {
                    lastErr = e
                    // HTTP status errors are not transient — surface immediately.
                    if (e.message?.startsWith("WebDAV HTTP") == true) throw e
                    if (attempt < backoffMs.size) {
                        android.util.Log.w(TAG, "WebDAV open attempt $attempt failed, retrying", e)
                        Thread.sleep(backoffMs[attempt])
                    }
                }
            }
            throw lastErr ?: IOException("WebDAV open failed for $safeUrl")
        }
        response = httpResponse

        val body = httpResponse.body ?: throw IOException("No response body from $safeUrl")
        // Buffered so skip() is efficient — an unbuffered network stream often
        // returns 0 from skip() and falls back to one byte per read, which makes
        // a large seek (200 with Range ignored) crawl.
        var stream = BufferedInputStream(body.byteStream(), 64 * 1024)

        // Some servers ignore the Range header and answer 200 with the *whole*
        // file. Skip to the requested offset so ExoPlayer gets the right bytes.
        if (httpResponse.code == 200 && dataSpec.position > 0) {
            var remaining = dataSpec.position
            while (remaining > 0) {
                val skipped = stream.skip(remaining)
                if (skipped > 0) {
                    remaining -= skipped
                } else if (stream.read() == -1) {
                    throw IOException("WebDAV unexpected EOF during seek for $safeUrl")
                } else {
                    remaining--
                }
            }
        }
        inputStream = stream

        // Determine content length
        val contentLength = body.contentLength()
        bytesRemaining = when {
            dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
            contentLength != -1L -> contentLength - if (httpResponse.code == 200) dataSpec.position else 0
            else -> C.LENGTH_UNSET.toLong()
        }

        transferStarted(dataSpec)
        android.util.Log.d(TAG, "open OK: remaining=$bytesRemaining")
        return bytesRemaining
    }

    override fun readFromStream(
        stream: InputStream,
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = try {
        stream.read(buffer, offset, length)
    } catch (e: IOException) {
        android.util.Log.w(TAG, "read error", e)
        throw e
    }

    override fun close() {
        try { inputStream?.close() } catch (_: Exception) {}
        try { response?.close() } catch (_: Exception) {}
        response = null
        resetSharedState()
        httpHost?.let { host ->
            ConnectionPool.returnWebDavClient(host, httpPort, useTls, webdavUser, webdavPass)
        }
        transferEnded()
    }

    companion object {
        private const val TAG = "WebDavDataSource"
    }
}

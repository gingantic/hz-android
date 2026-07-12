package com.rhnxdev.hzplayer.data.datasource.network

import com.rhnxdev.hzplayer.core.util.guessMimeType
import com.rhnxdev.hzplayer.core.util.sortedRemote
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.Credentials
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.StringReader
import java.text.SimpleDateFormat
import java.util.Locale

/**
 * A [RemoteBrowserClient] for WebDAV shares (HTTP/HTTPS with PROPFIND).
 *
 * Uses OkHttp for all HTTP calls. Connection reuse is managed by [ConnectionPool].
 */
class WebDavBrowserClient(
    private val host: String,
    private val port: Int,
    private val username: String,
    private val password: String,
    private val useTls: Boolean,
) : RemoteBrowserClient {

    private val baseUrl: String
        get() = "${if (useTls) "https" else "http"}://$host:$port"

    private val authHeader: String
        get() {
            if (username.isEmpty()) return ""
            // ponytail: never send Basic credentials over cleartext HTTP — that
            // transmits user:pass in base64 (trivially reversible) on the wire.
            // Over non-TLS we omit the header and warn; the server may still work
            // anonymously, or the user should switch to WEBDAVS.
            if (!useTls) {
                android.util.Log.w("WebDavBrowserClient",
                    "Refusing to send WebDAV credentials over cleartext HTTP to $host:$port — use WEBDAVS/TLS")
                return ""
            }
            return Credentials.basic(username, password)
        }

    override suspend fun connect() = withContext(Dispatchers.IO) {
        val client = ConnectionPool.borrowWebDavBrowser(host, port, useTls)
        val request = Request.Builder()
            .url("$baseUrl/")
            .header("Authorization", authHeader)
            .method("OPTIONS", null)
            .build()
        client.newCall(request).execute().use { response ->
            if (response.code == 401 || response.code == 403) {
                ConnectionPool.returnWebDavBrowser(host, port, useTls)
                throw com.rhnxdev.hzplayer.domain.model.RemoteAuthException()
            }
            if (!response.isSuccessful) {
                throw IOException("WebDAV server at $baseUrl returned ${response.code}")
            }
        }
    }

    override suspend fun listDirectory(path: String): List<RemoteFileItem> =
        withContext(Dispatchers.IO) {
            val client = ConnectionPool.borrowWebDavBrowser(host, port, useTls)
            val body = PROPFIND_REQUEST.toRequestBody(PROPFIND_MEDIA_TYPE)
            val request = Request.Builder()
                .url("$baseUrl${normalizePath(path)}")
                .header("Authorization", authHeader)
                .header("Depth", "1")
                .method("PROPFIND", body)
                .build()

            client.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    throw IOException("PROPFIND ${normalizePath(path)} returned ${response.code}")
                }
                val xml = response.body?.string() ?: return@withContext emptyList()
                parsePropfindMultistatus(xml, normalizePath(path))
            }
        }

    override suspend fun disconnect() = withContext(Dispatchers.IO) {
        ConnectionPool.returnWebDavBrowser(host, port, useTls)
    }

    // ── Helpers ─────────────────────────────────────────────────────

    private fun normalizePath(path: String): String {
        val cleaned = if (path.startsWith("/")) path else "/$path"
        // ponytail: reject path-traversal segments — a crafted server href or
        // client path with ".." must not escape the share root.
        return sanitizePath(cleaned)
    }

    /**
     * Strip `.`/`..` segments from a normalized path. Throws on traversal so
     * callers surface it instead of silently escaping the share root.
     */
    private fun sanitizePath(path: String): String {
        val out = mutableListOf<String>()
        for (seg in path.split('/')) {
            when {
                seg.isEmpty() || seg == "." -> {} // skip
                seg == ".." -> {
                    if (out.isNotEmpty()) out.removeAt(out.lastIndex)
                    else throw IOException("Path traversal denied: $path")
                }
                else -> out.add(seg)
            }
        }
        return "/" + out.joinToString("/")
    }

    // ── PROPFIND response XML parsing ───────────────────────────────

    /**
     * Parses a `multistatus` XML document from a PROPFIND response.
     *
     * Each `<response>` block yields one [RemoteFileItem]. The entry
     * matching the requested path (self-reference) is excluded.
     */
    private fun parsePropfindMultistatus(xml: String, requestedPath: String): List<RemoteFileItem> {
        val items = mutableListOf<RemoteFileItem>()
        val parser = newParser(xml)

        val normRequested = requestedPath.trimEnd('/').lowercase()
        var depth = 0
        var current: RemoteFileItemBuilder? = null

        try {
            var event = parser.eventType
            while (event != XmlPullParser.END_DOCUMENT) {
                when (event) {
                    XmlPullParser.START_TAG -> {
                        depth++
                        val tag = parser.name.lowercase()
                        when {
                            tag == "response" -> current = RemoteFileItemBuilder()
                            tag == "collection" -> current?.isCollection = true
                            tag == "displayname" && current != null -> {
                                current.name = parser.nextText()
                            }
                            tag == "getcontentlength" && current != null -> {
                                parser.nextText().toLongOrNull()?.let { current.fileSize = it }
                            }
                            tag == "getcontenttype" && current != null -> {
                                current.mimeType = parser.nextText()
                            }
                            tag == "getlastmodified" && current != null -> {
                                current.dateModified = parseWebDavDate(parser.nextText())
                            }
                            tag == "href" && current != null && depth == 2 -> {
                                current.href = parser.nextText()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        depth--
                        if (parser.name.lowercase() == "response" && current != null) {
                            val raw = current.href?.trimEnd('/')?.lowercase() ?: ""
                            val decoded = java.net.URLDecoder.decode(raw, "UTF-8")
                            val selfRef = decoded == normRequested ||
                                decoded == "$normRequested/" ||
                                decoded.removePrefix("/") == normRequested.removePrefix("/")
                            if (!selfRef && current.href != null) {
                                val hrefPath = current.href!!.substringBefore("?")
                                val name = current.name?.ifBlank { null }
                                    ?: hrefPath.substringAfterLast("/").trimEnd('/')
                                val filePath = sanitizePath(
                                    if (hrefPath.startsWith("/")) hrefPath else "/$hrefPath"
                                )
                                items.add(
                                    RemoteFileItem(
                                        name = name,
                                        path = filePath,
                                        isDirectory = current.isCollection,
                                        fileSize = current.fileSize,
                                        childCount = -1,
                                        dateModified = current.dateModified,
                                        mimeType = if (!current.isCollection) {
                                            current.mimeType ?: guessMimeType(name)
                                        } else null,
                                    )
                                )
                            }
                            current = null
                        }
                    }
                }
                event = parser.next()
            }
        } catch (_: XmlPullParserException) {
            // Malformed XML — return what we parsed so far
        } catch (_: IOException) {
            // Stream error — return what we parsed so far
        }

        return items.sortedRemote()
    }

    private class RemoteFileItemBuilder {
        var href: String? = null
        var name: String? = null
        var fileSize: Long = 0
        var mimeType: String? = null
        var dateModified: Long = 0
        var isCollection: Boolean = false
    }

    private fun newParser(xml: String): XmlPullParser {
        val parser = org.xmlpull.v1.XmlPullParserFactory.newInstance().newPullParser()
        parser.setFeature(XmlPullParser.FEATURE_PROCESS_NAMESPACES, true)
        parser.setInput(StringReader(xml))
        return parser
    }

    companion object {
        private val PROPFIND_REQUEST = """<?xml version="1.0" encoding="utf-8"?>
<D:propfind xmlns:D="DAV:">
  <D:prop>
    <D:displayname/>
    <D:getcontentlength/>
    <D:getcontenttype/>
    <D:getlastmodified/>
    <D:resourcetype/>
  </D:prop>
</D:propfind>"""

        private val PROPFIND_MEDIA_TYPE = "application/xml; charset=utf-8".toMediaType()

        // Patterns only — SimpleDateFormat is NOT thread-safe, so a fresh instance is
        // created per parse call (this runs from multiple ConnectionPool threads).
        private val DATE_PATTERNS = listOf(
            "EEE, dd MMM yyyy HH:mm:ss 'GMT'",
            "yyyy-MM-dd'T'HH:mm:ss'Z'",
            "yyyy-MM-dd'T'HH:mm:ssXXX",
        )

        private fun parseWebDavDate(dateStr: String): Long {
            for (pattern in DATE_PATTERNS) {
                try {
                    val format = SimpleDateFormat(pattern, Locale.US)
                    format.timeZone = java.util.TimeZone.getTimeZone("UTC")
                    return format.parse(dateStr)?.time ?: 0
                } catch (_: Exception) {}
            }
            return 0
        }
    }
}

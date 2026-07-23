package com.rhnxdev.hzplayer.browser.media

import android.net.Uri
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.net.URLDecoder
import java.util.Locale
import java.util.concurrent.TimeUnit

object MediaSnifferEngine {
    private const val TAG = "MediaSnifferEngine"

    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .followRedirects(true)
            .build()
    }

    private val VIDEO_EXTENSIONS = setOf(
        "mp4", "m3u8", "mpd", "webm", "mkv", "mov", "flv", "avi", "3gp", "ts"
    )

    private val AUDIO_EXTENSIONS = setOf(
        "mp3", "m4a", "aac", "wav", "ogg", "flac", "opus", "wma"
    )

    private val IGNORED_DOMAINS = setOf(
        "google-analytics.com", "googletagmanager.com", "doubleclick.net",
        "facebook.com", "analytics"
    )

    private val STATIC_ASSET_EXTENSIONS = setOf(
        "js", "css", "png", "jpg", "jpeg", "gif", "svg", "ico", "woff", "woff2", "ttf", "eot", "vtt", "srt", "json", "html", "txt"
    )

    private val MEDIA_REGEX_PATTERNS = listOf(
        Regex("""(?i)\.(m3u8|mpd|mp4|webm|mkv|mov|flv|avi|3gp)(\?|#|$)"""),
        Regex("""(?i)(/hls/|/dash/|/playlist/|/manifest/|master\.m3u8|index\.m3u8|playlist\.m3u8|manifest\.mpd)"""),
        Regex("""(?i)[?&](url|file|src|stream|media|video|link)=[^&]*\.(m3u8|mpd|mp4|webm|mkv)"""),
        Regex("""(?i)[?&](format|type)=(hls|m3u8|mpd|dash|mp4)""")
    )

    /**
     * Quick check if a URL or request headers look like a media resource.
     */
    fun isMediaUrl(url: String, headers: Map<String, String>? = null): Boolean {
        if (url.isBlank() || url.startsWith("data:") || url.startsWith("blob:")) return false

        val lowerUrl = url.lowercase(Locale.ROOT)
        if (IGNORED_DOMAINS.any { lowerUrl.contains(it) }) return false

        // Check if disguised HLS stream (.txt playlist, master URL, mpegurl mime)
        val contentTypeHeader = headers?.get("content-type") ?: ""
        val isDisguisedHls = MediaStreamDecoder.isDisguisedHlsStream(url, contentTypeHeader)

        // Check file extension
        val ext = getExtension(url).lowercase(Locale.ROOT)
        if (STATIC_ASSET_EXTENSIONS.contains(ext) && !isDisguisedHls) return false

        if (VIDEO_EXTENSIONS.contains(ext) || AUDIO_EXTENSIONS.contains(ext) || isDisguisedHls) {
            return true
        }

        // Check regex stream patterns
        if (MEDIA_REGEX_PATTERNS.any { it.containsMatchIn(url) }) {
            return true
        }

        // Check path keywords
        if (lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") || lowerUrl.contains("/hls/") || lowerUrl.contains("/videoplayback")) {
            return true
        }

        // Check headers if present
        headers?.let { map ->
            val contentType = map["content-type"]?.lowercase(Locale.ROOT) ?: ""
            if (contentType.startsWith("video/") || contentType.startsWith("audio/") ||
                contentType.contains("application/x-mpegurl") ||
                contentType.contains("application/vnd.apple.mpegurl") ||
                contentType.contains("application/dash+xml")
            ) {
                return true
            }
        }

        return false
    }

    private val SECURITY_TOKEN_KEYS = setOf(
        "token", "auth", "hdnts", "key", "sig", "signature", "expires",
        "st", "et", "h", "hash", "v", "pass", "access_token", "jwt", "bearer", "ticket", "sec"
    )

    /**
     * Inspect and build a [DetectedMediaItem] from a captured URL.
     */
    fun createMediaItem(
        rawUrl: String,
        pageUrl: String,
        pageTitle: String,
        requestHeaders: Map<String, String> = emptyMap(),
        mimeType: String = "",
        contentLength: Long = -1L
    ): DetectedMediaItem {
        val ext = getExtension(rawUrl).lowercase(Locale.ROOT)
        val mediaType = determineMediaType(rawUrl, ext, mimeType)
        val title = deriveTitle(rawUrl, pageTitle)
        val formattedSize = formatFileSize(contentLength)

        val mergedHeaders = mutableMapOf<String, String>()
        val refererVal = requestHeaders["Referer"] ?: requestHeaders["referer"] ?: pageUrl
        if (refererVal.isNotBlank()) {
            mergedHeaders["Referer"] = refererVal
        }

        val forbidden = setOf(
            "host", "content-length", "connection", "accept-encoding",
            "content-type", "transfer-encoding", "if-modified-since", "if-none-match", "range", "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest"
        )

        requestHeaders.forEach { (k, v) ->
            val lower = k.trim().lowercase(Locale.ROOT)
            if (k.isNotBlank() && v.isNotBlank() && !lower.equals("referer") && !forbidden.contains(lower)) {
                val normalizedKey = when (lower) {
                    "user-agent" -> "User-Agent"
                    "cookie" -> "Cookie"
                    "authorization" -> "Authorization"
                    "origin" -> "Origin"
                    "accept" -> "Accept"
                    "accept-language" -> "Accept-Language"
                    else -> k.trim()
                }
                mergedHeaders[normalizedKey] = v
            }
        }

        // Pull active web cookies if missing from network request headers
        if (mergedHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }) {
            val targetUrl = pageUrl.ifBlank { rawUrl }
            val liveCookie = runCatching { android.webkit.CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
            if (!liveCookie.isNullOrBlank()) {
                mergedHeaders["Cookie"] = liveCookie
            }
        }

        val detectedTokens = extractTokens(rawUrl)

        return DetectedMediaItem(
            url = rawUrl,
            pageUrl = pageUrl,
            title = title,
            mimeType = mimeType.ifBlank { guessMimeType(ext, mediaType) },
            mediaType = mediaType,
            extension = ext,
            contentLength = contentLength,
            formattedSize = formattedSize,
            headers = mergedHeaders,
            detectedTokens = detectedTokens
        )
    }

    /**
     * Extract security/auth tokens and signature parameters from URL query parameters.
     */
    fun extractTokens(url: String): Map<String, String> {
        val tokens = mutableMapOf<String, String>()
        try {
            val uri = Uri.parse(url)
            if (uri.isHierarchical) {
                uri.queryParameterNames?.forEach { param ->
                    val lowerParam = param.lowercase(Locale.ROOT)
                    if (SECURITY_TOKEN_KEYS.contains(lowerParam) || lowerParam.contains("token") || lowerParam.contains("sig") || lowerParam.contains("auth")) {
                        uri.getQueryParameter(param)?.let { valStr ->
                            if (valStr.isNotBlank()) {
                                tokens[param] = valStr
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to extract tokens from URL: ${e.message}")
        }
        return tokens
    }

    /**
     * Asynchronously parse HLS `.m3u8` master playlist to extract multi-resolution streams.
     */
    suspend fun parseHlsQualities(item: DetectedMediaItem): DetectedMediaItem = withContext(Dispatchers.IO) {
        val lowerUrl = item.url.lowercase(Locale.ROOT)
        val isPossibleHls = item.mediaType == MediaType.STREAM_HLS || lowerUrl.contains(".m3u8") ||
                MediaStreamDecoder.isDisguisedHlsStream(item.url, item.mimeType)
        if (!isPossibleHls) {
            return@withContext item
        }

        try {
            val reqBuilder = Request.Builder().url(item.url)
            item.headers.forEach { (k, v) ->
                if (k.isNotBlank() && v.isNotBlank()) {
                    reqBuilder.header(k, v)
                }
            }

            val response = httpClient.newCall(reqBuilder.build()).execute()
            if (!response.isSuccessful) return@withContext item

            val body = response.body?.string() ?: return@withContext item
            if (!body.contains("#EXTM3U")) return@withContext item

            val qualities = parseM3u8Content(body, item.url)
            if (qualities.isNotEmpty()) {
                val sortedQualities = qualities.sortedByDescending { it.bitrate }
                val bestQuality = sortedQualities.firstOrNull()
                return@withContext item.copy(
                    subQualities = sortedQualities,
                    selectedQualityUrl = bestQuality?.url ?: item.url,
                    qualityLabel = bestQuality?.resolution ?: item.qualityLabel
                )
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to parse M3U8 playlist: ${e.message}")
        }

        return@withContext item
    }

    private fun parseM3u8Content(body: String, baseUrl: String): List<DetectedMediaQuality> {
        val list = mutableListOf<DetectedMediaQuality>()
        val lines = body.lines()

        var currentResolution = ""
        var currentBandwidth = 0L

        for (i in lines.indices) {
            val line = lines[i].trim()
            if (line.startsWith("#EXT-X-STREAM-INF:")) {
                currentResolution = extractAttr(line, "RESOLUTION")
                currentBandwidth = extractAttr(line, "BANDWIDTH").toLongOrNull() ?: 0L
            } else if (!line.startsWith("#") && line.isNotBlank()) {
                val targetUrl = resolveRelativeUrl(baseUrl, line)
                val label = buildQualityLabel(currentResolution, currentBandwidth)
                list.add(
                    DetectedMediaQuality(
                        url = targetUrl,
                        resolution = currentResolution.ifBlank { "Auto" },
                        bitrate = currentBandwidth,
                        label = label
                    )
                )
                currentResolution = ""
                currentBandwidth = 0L
            }
        }

        return list
    }

    private fun extractAttr(line: String, attrName: String): String {
        val pattern = Regex("$attrName=([^,\\s\"]+|\"[^\"]+\")")
        val match = pattern.find(line) ?: return ""
        return match.groupValues[1].replace("\"", "")
    }

    private fun buildQualityLabel(res: String, bandwidth: Long): String {
        val height = res.substringAfter("x", res).takeIf { it.isNotBlank() && it != res }
        val resLabel = if (height != null) "${height}p" else res.ifBlank { "HD" }

        return if (bandwidth > 0) {
            val mbps = String.format(Locale.ROOT, "%.1f Mbps", bandwidth / 1_000_000.0)
            if (resLabel.isNotBlank()) "$resLabel ($mbps)" else mbps
        } else {
            resLabel
        }
    }

    private fun resolveRelativeUrl(baseUrl: String, relativeUrl: String): String {
        return try {
            val baseUri = Uri.parse(baseUrl)
            val resolved = if (relativeUrl.startsWith("http://") || relativeUrl.startsWith("https://")) {
                relativeUrl
            } else if (relativeUrl.startsWith("/")) {
                "${baseUri.scheme}://${baseUri.host}${if (baseUri.port != -1) ":${baseUri.port}" else ""}$relativeUrl"
            } else {
                val path = baseUri.path?.substringBeforeLast('/') ?: ""
                "${baseUri.scheme}://${baseUri.host}${if (baseUri.port != -1) ":${baseUri.port}" else ""}$path/$relativeUrl"
            }

            val resolvedUri = Uri.parse(resolved)
            if (resolvedUri.query.isNullOrBlank() && !baseUri.query.isNullOrBlank()) {
                val connector = if (resolved.contains("?")) "&" else "?"
                "$resolved$connector${baseUri.query}"
            } else {
                resolved
            }
        } catch (_: Exception) {
            relativeUrl
        }
    }

    private fun getExtension(url: String): String {
        return try {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            cleanUrl.substringAfterLast('.', "")
        } catch (_: Exception) {
            ""
        }
    }

    private fun determineMediaType(url: String, ext: String, mime: String): MediaType {
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerMime = mime.lowercase(Locale.ROOT)

        return when {
            MediaStreamDecoder.isDisguisedHlsStream(url, mime) -> MediaType.STREAM_HLS
            ext == "m3u8" || lowerUrl.contains(".m3u8") || lowerMime.contains("mpegurl") -> MediaType.STREAM_HLS
            ext == "mpd" || lowerUrl.contains(".mpd") || lowerMime.contains("dash") -> MediaType.STREAM_DASH
            AUDIO_EXTENSIONS.contains(ext) || lowerMime.startsWith("audio/") -> MediaType.AUDIO
            VIDEO_EXTENSIONS.contains(ext) || lowerMime.startsWith("video/") -> MediaType.VIDEO
            else -> MediaType.OTHER
        }
    }

    private fun guessMimeType(ext: String, mediaType: MediaType): String {
        return when (ext) {
            "mp4" -> "video/mp4"
            "m3u8" -> "application/x-mpegURL"
            "mpd" -> "application/dash+xml"
            "webm" -> "video/webm"
            "mkv" -> "video/x-matroska"
            "mp3" -> "audio/mpeg"
            "m4a" -> "audio/mp4"
            "aac" -> "audio/aac"
            "wav" -> "audio/wav"
            else -> when (mediaType) {
                MediaType.VIDEO, MediaType.STREAM_HLS, MediaType.STREAM_DASH -> "video/*"
                MediaType.AUDIO -> "audio/*"
                else -> "*/*"
            }
        }
    }

    private fun deriveTitle(url: String, pageTitle: String): String {
        try {
            val cleanUrl = url.substringBefore('?').substringBefore('#')
            val fileName = cleanUrl.substringAfterLast('/')
            val decoded = URLDecoder.decode(fileName, "UTF-8")
            if (decoded.isNotBlank() && decoded.length > 3 && !decoded.startsWith("index.")) {
                return decoded
            }
        } catch (_: Exception) {}

        return pageTitle.ifBlank { "Media Stream" }
    }

    fun formatFileSize(bytes: Long): String {
        if (bytes <= 0) return "Stream / Unknown"
        val kb = bytes / 1024.0
        val mb = kb / 1024.0
        val gb = mb / 1024.0

        return when {
            gb >= 1.0 -> String.format(Locale.ROOT, "%.2f GB", gb)
            mb >= 1.0 -> String.format(Locale.ROOT, "%.1f MB", mb)
            else -> String.format(Locale.ROOT, "%.0f KB", kb)
        }
    }
}

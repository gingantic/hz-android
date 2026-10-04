package com.rhnxdev.hzplayer.core.util

import android.net.Uri
import android.util.Log
import java.util.Locale

private const val TAG = "HttpHeaderSanitizer"

/** Chrome-like UA used when the browser did not supply one (same value as the ExoPlayer path). */
const val DEFAULT_WEB_USER_AGENT =
    "Mozilla/5.0 (Linux; Android 10; K) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"

/** Headers FFmpeg or the server must own; forwarding them breaks ranges, framing or TLS. */
private val FORBIDDEN_HEADERS = setOf(
    "host", "content-length", "connection", "accept-encoding",
    "content-type", "transfer-encoding", "if-modified-since", "if-none-match", "range",
    "sec-fetch-mode", "sec-fetch-site", "sec-fetch-dest",
)

private val CANONICAL_HEADERS = mapOf(
    "referer" to "Referer",
    "user-agent" to "User-Agent",
    "cookie" to "Cookie",
    "authorization" to "Authorization",
    "origin" to "Origin",
    "accept" to "Accept",
    "accept-language" to "Accept-Language",
)

/** RFC 7230 token: the only characters allowed in a header field name. */
private val HEADER_TOKEN = Regex("^[!#$%&'*+.^_`|~0-9A-Za-z-]+$")

/**
 * Normalizes browser-sniffed request headers before they reach the native FFmpeg HTTP stack.
 *
 * Rules 1-5 mirror `MediaPlayerHolder.setHttpRequestHeaders` so both engines send the same
 * request. Rules 6-7 (RFC 7230 token keys, no control characters in values) are extra and
 * prevent header injection through the native `headers` option.
 *
 * @param headers — raw headers from the browser/intent
 * @return a new, ordered map that is safe to join as `Key: Value\r\n` lines
 */
fun sanitizeHttpRequestHeaders(headers: Map<String, String>): Map<String, String> {
    val filtered = LinkedHashMap<String, String>()
    headers.forEach { (rawKey, value) ->
        val keyLower = rawKey.trim().lowercase(Locale.ROOT)
        if (keyLower.isBlank() || value.isBlank() || keyLower in FORBIDDEN_HEADERS) return@forEach
        filtered[CANONICAL_HEADERS[keyLower] ?: rawKey.trim()] = value
    }

    val referer = filtered["Referer"]
    if (!referer.isNullOrBlank() && filtered["Origin"].isNullOrBlank()) {
        try {
            val refUri = Uri.parse(referer)
            val scheme = refUri.scheme
            val host = refUri.host
            if (!scheme.isNullOrBlank() && !host.isNullOrBlank()) {
                val port = if (refUri.port != -1) ":${refUri.port}" else ""
                filtered["Origin"] = "$scheme://$host$port"
            }
        } catch (_: Exception) {
            // An unparsable Referer simply yields no derived Origin.
        }
    }

    if (filtered.none { it.key.equals("User-Agent", ignoreCase = true) }) {
        filtered["User-Agent"] = DEFAULT_WEB_USER_AGENT
    }

    val safe = LinkedHashMap<String, String>()
    filtered.forEach { (key, value) ->
        if (HEADER_TOKEN.matches(key) && value.none { it.isUnsafeHeaderChar() }) {
            safe[key] = value
        } else {
            Log.w(TAG, "Dropped unsafe HTTP header: $key")
        }
    }
    return safe
}

private fun Char.isUnsafeHeaderChar(): Boolean = (this < ' ' && this != '\t') || this == '\u007F'

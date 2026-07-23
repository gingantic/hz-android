package com.rhnxdev.hzplayer.browser.media

import java.util.Locale

/**
 * Utility for detecting, decoding, and resolving disguised/obfuscated media streams
 * (such as HLS master playlists masked as .txt files and video segments masked as .woff/.woff2 files).
 */
object MediaStreamDecoder {

    private val DISGUISED_HLS_PATTERNS = listOf(
        "cl-master",
        "master",
        "playlist",
        "index-f",
        "stream-f",
        "vnd.apple.mpegurl"
    )

    /**
     * Checks if a URL or request signature corresponds to a disguised/obfuscated HLS stream.
     */
    fun isDisguisedHlsStream(url: String, mimeType: String = ""): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerMime = mimeType.lowercase(Locale.ROOT)

        if (lowerMime.contains("mpegurl") || lowerMime.contains("m3u8")) return true
        if (lowerUrl.contains(".m3u8")) return true

        // Check if filename/path matches disguised playlist patterns (e.g. cl-master...txt, index-f1-v1-a1.txt)
        return DISGUISED_HLS_PATTERNS.any { lowerUrl.contains(it) }
    }

    /**
     * Checks if a URL represents a segment chunk disguised as a font asset (.woff, .woff2).
     */
    fun isDisguisedSegment(url: String): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        return (lowerUrl.contains(".woff") || lowerUrl.contains(".woff2")) &&
                (lowerUrl.contains("seg-") || lowerUrl.contains("segment") || lowerUrl.contains("init-"))
    }

    /**
     * Resolves the canonical MIME type for ExoPlayer / Media3 player engine.
     */
    fun resolveCanonicalMimeType(url: String, rawMimeType: String = ""): String {
        if (isDisguisedHlsStream(url, rawMimeType)) {
            return "application/x-mpegURL"
        }
        return rawMimeType
    }
}

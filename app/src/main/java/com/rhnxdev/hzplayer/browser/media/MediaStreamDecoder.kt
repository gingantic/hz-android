package com.rhnxdev.hzplayer.browser.media

import java.util.Locale

/**
 * Utility for detecting, decoding, and resolving disguised/obfuscated media streams
 * (such as HLS master playlists masked as .txt/.json files and video segments masked as .woff/.woff2 files).
 */
object MediaStreamDecoder {

    private val KNOWN_PROGRESSIVE_EXTENSIONS = setOf(
        "mp4", "mkv", "webm", "flv", "mov", "avi", "3gp", "wmv", "mp3", "m4a", "aac", "flac", "ogg", "wav", "opus"
    )

    private val DISGUISED_HLS_PATTERNS = listOf(
        "cl-master",
        "master",
        "playlist",
        "manifest",
        "index-f",
        "stream-f",
        "vnd.apple.mpegurl",
        "master.txt",
        "playlist.txt",
        "index.txt",
        "manifest.txt",
        "master.json",
        "playlist.json",
        "manifest.json"
    )

    private val MASTER_KEYWORDS = listOf(
        "master",
        "cl-master",
        "master.m3u8",
        "master.mpd",
        "master.txt",
        "master.json",
        "master_playlist",
        "manifest.m3u8",
        "manifest.mpd",
        "playlist.m3u8",
        "playlist.mpd"
    )

    /**
     * Checks if a URL represents a Master Stream or Master Playlist.
     */
    fun isMasterStreamUrl(url: String, mimeType: String = ""): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerMime = mimeType.lowercase(Locale.ROOT)

        if (lowerMime.contains("mpegurl") || lowerMime.contains("m3u8") || lowerMime.contains("dash")) {
            if (lowerUrl.contains("master") || lowerUrl.contains("manifest") || lowerUrl.contains("playlist")) return true
        }

        val path = lowerUrl.substringBefore('?').substringBefore('#')
        if (path.contains("master") || path.endsWith("/master") || path.contains("cl-master")) return true

        return MASTER_KEYWORDS.any { lowerUrl.contains(it) }
    }

    /**
     * Checks if a URL or request signature corresponds to a disguised/obfuscated HLS stream.
     */
    fun isDisguisedHlsStream(url: String, mimeType: String = ""): Boolean {
        if (url.isBlank()) return false
        val lowerUrl = url.lowercase(Locale.ROOT)
        val lowerMime = mimeType.lowercase(Locale.ROOT)

        if (lowerMime.contains("mpegurl") || lowerMime.contains("m3u8")) return true
        
        val path = lowerUrl.substringBefore('?').substringBefore('#')
        if (path.endsWith(".m3u8")) return true

        val ext = path.substringAfterLast('.', "")
        if (KNOWN_PROGRESSIVE_EXTENSIONS.contains(ext)) {
            return false
        }

        // Check if filename/path matches disguised playlist patterns
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

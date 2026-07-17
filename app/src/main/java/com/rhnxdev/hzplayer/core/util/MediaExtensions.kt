package com.rhnxdev.hzplayer.core.util

import android.util.Log
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.RemoteFileItem
import com.rhnxdev.hzplayer.domain.model.SortType
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

val VIDEO_EXTENSIONS = setOf(
    "mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "3gp",
    "m4v", "mpg", "mpeg", "ts", "mts", "vob", "m3u8", "mpd",
)

val AUDIO_EXTENSIONS = setOf(
    "mp3", "flac", "wav", "ogg", "aac", "wma", "m4a",
    "opus", "ape", "aiff", "dsf", "dff",
)

val SUBTITLE_EXTENSIONS = setOf("srt", "vtt", "ass", "ssa", "sub", "lrc")

val DOCUMENT_EXTENSIONS = setOf(
    "pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx",
    "txt", "rtf", "odt", "ods", "odp", "csv", "json", "xml",
)

val BINARY_EXTENSIONS = setOf(
    "exe", "msi", "apk", "aab", "deb", "rpm", "bin", "elf",
    "dll", "so", "dylib", "jar", "class", "wasm",
    "appimage", "dmg", "pkg",
    "bat", "cmd", "com", "scr",
    "sh", "bash", "zsh",
)

/**
 * True when [subtitleName] is a neighbor subtitle of the video whose base name is
 * [videoBaseName]. Matches an exact base ("movie.srt" for "movie.mkv") AND
 * language-tagged variants ("movie.en.srt", "movie.eng.forced.ass"). Case-insensitive.
 */
fun isNeighborSubtitleName(subtitleName: String, videoBaseName: String): Boolean {
    val ext = subtitleName.substringAfterLast('.', "").lowercase()
    if (ext !in SUBTITLE_EXTENSIONS) return false
    val subBase = subtitleName.substringBeforeLast('.')
    return subBase.equals(videoBaseName, ignoreCase = true) ||
        subBase.startsWith("$videoBaseName.", ignoreCase = true)
}

fun isVideoExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").substringBefore('?').lowercase()
    return ext in VIDEO_EXTENSIONS
}

fun isAudioExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in AUDIO_EXTENSIONS
}

fun isBinaryExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in BINARY_EXTENSIONS
}

fun isDocumentExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in DOCUMENT_EXTENSIONS
}

fun defaultPort(protocol: NetworkProtocol): Int = when (protocol) {
    NetworkProtocol.FTP -> 21
    NetworkProtocol.SFTP -> 22
    NetworkProtocol.SMB -> 445
    NetworkProtocol.WEBDAV -> 80
    NetworkProtocol.WEBDAVS -> 443
}


/**
 * Probe a URL's Content-Type via HEAD (falling back to a ranged GET) so we can
 * decide video/audio for extensionless stream URLs (e.g. bucket URLs like
 * https://bucket.s3.amazonaws.com/v/abc123). Returns the lower-cased MIME type
 * (without parameters) or null if it can't be determined.
 */
suspend fun probeContentType(url: String): String? = withContext(Dispatchers.IO) {
    probeContentTypeOnce(url, "HEAD") ?: probeContentTypeOnce(url, "GET")
}

private fun probeContentTypeOnce(url: String, method: String): String? {
    var conn: HttpURLConnection? = null
    return try {
        conn = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            setRequestProperty("Range", "bytes=0-0")
            connectTimeout = 5_000
            readTimeout = 5_000
            instanceFollowRedirects = true
        }
        val code = conn.responseCode
        val ct = conn.contentType
        if ((code == HttpURLConnection.HTTP_OK || code == HttpURLConnection.HTTP_PARTIAL) &&
            !ct.isNullOrBlank()
        ) {
            ct.substringBefore(';').trim().lowercase()
        } else {
            null
        }
    } catch (e: Exception) {
        Log.w("MediaExtensions", "stream probe failed for $url", e)
        null
    } finally {
        try { conn?.disconnect() } catch (_: Exception) {}
    }
}

/**
 * Pure mapping from a Content-Type header to a video decision.
 * video/ MIME -> true, audio/ -> false, everything else (null /
 * application/octet-stream / unknown) -> true (video default for buckets).
 * Side-effect free so it can be unit tested without network.
 */
fun isVideoContentType(contentType: String?): Boolean {
    val type = contentType?.substringBefore(';')?.trim()?.lowercase() ?: return true
    return when {
        type.startsWith("audio/") -> false
        type.startsWith("video/") -> true
        else -> true
    }
}

/**
 * Decide whether a stream URL is video, using the server Content-Type when the
 * URL has no recognizable extension. Recognized extensions win; for extensionless
 * http(s) URLs probe the header. Non-http schemes default to video.
 */
suspend fun isVideoStreamUrl(url: String): Boolean {
    if (isVideoExtension(url)) return true
    if (isAudioExtension(url)) return false
    if (!url.startsWith("http://", true) && !url.startsWith("https://", true)) return true
    return isVideoContentType(probeContentType(url))
}

/**
 * Synchronous, no-network fallback used for history/remote items: treat any
 * extensionless URL as video so the video surface opens (ExoPlayer sniffs the
 * container). Use isVideoStreamUrl for the pasted-stream path when accuracy
 * matters (it probes the server header).
 */
fun isVideoOrStreamDefault(url: String): Boolean {
    if (isVideoExtension(url)) return true
    if (isAudioExtension(url)) return false
    return true
}

/**
 * Sort remote listings dirs-first, then by name (case-insensitive). Shared by
 * every protocol browser client so ordering stays consistent.
 */
fun List<RemoteFileItem>.sortedRemote(): List<RemoteFileItem> =
    sortedWith(
        compareByDescending<RemoteFileItem> { it.isDirectory }
            .thenBy { it.name.lowercase() },
    )

/**
 * Sort a file listing dirs-first, then by the chosen [SortType] (name /
 * date-modified / size). Generic over both [FolderItem] and [RemoteFileItem],
 * which expose the same accessors, so the local and remote VMs share one impl.
 */
fun <T> sortFilesByType(
    items: List<T>,
    sort: SortType,
    isDirectory: (T) -> Boolean,
    name: (T) -> String,
    dateModified: (T) -> Long,
    size: (T) -> Long,
    descending: Boolean = false,
): List<T> {
    val (dirs, files) = items.partition(isDirectory)
    val sortDirs = when (sort) {
        SortType.TITLE -> dirs.sortedBy { name(it).lowercase() }
        SortType.DATE_MODIFIED -> dirs.sortedByDescending(dateModified)
        SortType.FILE_SIZE -> dirs.sortedByDescending(size)
        else -> dirs.sortedBy { name(it).lowercase() }
    }
    val sortFiles = when (sort) {
        SortType.TITLE -> files.sortedBy { name(it).lowercase() }
        SortType.DATE_MODIFIED -> files.sortedByDescending(dateModified)
        SortType.FILE_SIZE -> files.sortedByDescending(size)
        else -> files.sortedBy { name(it).lowercase() }
    }
    val orderedDirs = if (descending) sortDirs.asReversed() else sortDirs
    val orderedFiles = if (descending) sortFiles.asReversed() else sortFiles
    return orderedDirs + orderedFiles
}


package com.rhnxdev.hzplayer.core.util

import com.rhnxdev.hzplayer.core.components.BreadcrumbItem
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Archive container formats browsable in-place via libarchive (no extraction).
 * lz4 is intentionally absent — the bundled libarchive is not built with it.
 */
val ARCHIVE_EXTENSIONS = setOf(
    "zip", "7z", "rar", "tar", "iso", "cab",
    "gz", "tgz", "bz2", "tbz2", "xz", "txz", "cpio",
)

fun isArchiveExtension(name: String): Boolean {
    val ext = name.substringAfterLast('.', "").lowercase()
    return ext in ARCHIVE_EXTENSIONS
}

/**
 * Playback URI for a single entry inside an archive:
 * `archive:///<urlEncContainer>/<urlEncEntry>`. A literal "/" separates the two
 * url-encoded halves (real slashes are encoded, so the split is unambiguous).
 * Shared by [com.rhnxdev.hzplayer.data.datasource.archive.ArchiveDataSource].
 */
object ArchiveUri {
    const val SCHEME = "archive"

    fun build(container: String, entry: String, password: String? = null): String {
        val base = "archive:///" + URLEncoder.encode(container, "UTF-8") + "/" +
                URLEncoder.encode(entry, "UTF-8")
        return if (password != null) "$base?password=${URLEncoder.encode(password, "UTF-8")}" else base
    }

    /** @return Triple of container path, entry name, and password, or null if [uri] is malformed. */
    fun parse(uri: String): Triple<String, String, String?>? {
        val queryIdx = uri.indexOf('?')
        val cleanUri = if (queryIdx >= 0) uri.substring(0, queryIdx) else uri
        val query = if (queryIdx >= 0) uri.substring(queryIdx + 1) else null

        val path = cleanUri.substringAfter("archive:///", "").ifEmpty { return null }
        val idx = path.indexOf('/')
        if (idx <= 0) return null
        val container = URLDecoder.decode(path.substring(0, idx), "UTF-8")
        val entry = URLDecoder.decode(path.substring(idx + 1), "UTF-8")

        var password: String? = null
        if (query != null) {
            val parts = query.split('&')
            for (part in parts) {
                if (part.startsWith("password=")) {
                    password = URLDecoder.decode(part.substring("password=".length), "UTF-8")
                }
            }
        }
        return Triple(container, entry, password)
    }
}

/**
 * Virtual File-Browser path for navigating *inside* an archive:
 * `archivebrowse:<container>\n<innerPrefix>` where innerPrefix is "" at the
 * container root or a "dir/subdir/" path. Newline never appears in real file
 * paths, so it is a safe separator. Distinct from [ArchiveUri] which addresses a
 * playable entry; this addresses a directory level for listing.
 */
object ArchiveBrowsePath {
    private const val PREFIX = "archivebrowse:"
    private const val SEP = "\n"

    fun build(container: String, innerPrefix: String): String =
        "$PREFIX$container$SEP$innerPrefix"

    fun isArchiveBrowsePath(path: String): Boolean = path.startsWith(PREFIX)

    /** True when [path] is a real filesystem path (not a virtual archive path). */
    fun isRealFilePath(path: String): Boolean =
        !path.startsWith(PREFIX) && !path.startsWith("${ArchiveUri.SCHEME}:")

    /** @return container path to innerPrefix. */
    fun parse(path: String): Pair<String, String> {
        val body = path.removePrefix(PREFIX)
        val sep = body.indexOf(SEP)
        return if (sep < 0) body to "" else body.substring(0, sep) to body.substring(sep + 1)
    }
}

/**
 * Breadcrumbs for an archive browse level: [container.zip, dir, subdir, …].
 * Each crumb carries an [ArchiveBrowsePath] so a crumb tap re-lists that level.
 */
fun buildArchiveBreadcrumbs(container: String, innerPrefix: String): List<BreadcrumbItem> {
    val crumbs = mutableListOf(
        BreadcrumbItem(container.substringAfterLast('/'), ArchiveBrowsePath.build(container, "")),
    )
    if (innerPrefix.isEmpty()) return crumbs
    var acc = ""
    for (part in innerPrefix.trimEnd('/').split('/').filter { it.isNotEmpty() }) {
        acc += "$part/"
        crumbs.add(BreadcrumbItem(part, ArchiveBrowsePath.build(container, acc)))
    }
    return crumbs
}

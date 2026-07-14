package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import android.os.SystemClock
import jcifs.CIFSContext
import jcifs.smb.SmbFile
import jcifs.smb.SmbAuthException
import java.util.concurrent.ConcurrentHashMap

/**
 * Resolves an SMB path to a jcifs [SmbFile] by walking the directory tree with
 * [SmbFile.listFiles], matching each segment by its decoded name — never by
 * placing the segment into a URL.
 *
 * ## Why
 * Constructing an [SmbFile] from a URL that contains the target segment is
 * unreliable in jcifs-ng: it does not decode `%`-escapes the way the SMB server
 * expects. In practice:
 * - ASCII names with `%20` (spaces) resolve to a literal "%20" → *file not found*.
 * - Names with emoji (🚫), fullwidth CJK punctuation (？！), etc. get mangled into
 *   an illegal wire name → *STATUS_OBJECT_NAME_INVALID* ("syntax is incorrect").
 *
 * [SmbFile] objects returned by [SmbFile.listFiles] instead carry the server's
 * own correct encoding, so operations on them (length, open, listFiles) work for
 * any legal filename.
 *
 * ## Share level
 * The first segment (the share name) is built from a URL rather than walked.
 * Share names are effectively always ASCII, and this avoids requiring share
 * enumeration on the server root (`smb://host/`), which some servers reject.
 *
 * ## Caching
 * Intermediate directory listings are cached for [TTL_MS] and shared across all
 * callers (player, thumbnails, browser). This turns a burst of lookups in one
 * folder (e.g. a screen full of thumbnails) into a single `listFiles` round-trip,
 * and lets seek-driven reopens skip re-walking. Matching is case-insensitive
 * (SMB/Windows filesystems are case-insensitive, so this cannot pick the wrong
 * file).
 */
internal object SmbPathResolver {

    private const val TAG = "SmbPathResolver"
    private const val TTL_MS = 30_000L

    private class CachedListing(val timestamp: Long, val byName: Map<String, SmbFile>)

    /** Keyed by context identity — see [listChildren]. WeakHashMap so released contexts
     *  (e.g. after pool eviction) don't leak. */
    private val listingCache = java.util.WeakHashMap<CIFSContext, MutableMap<String, CachedListing>>()

    /**
     * Split an [Uri.encodedPath] into decoded segments below the server root.
     * e.g. `/E/snusnu/%E3%80%90file%E3%80%91.mkv` → `["E", "snusnu", "【file】.mkv"]`.
     */
    fun decodedSegmentsOf(encodedPath: String?): List<String> =
        (encodedPath ?: "/").removePrefix("/").split('/')
            .filter { it.isNotEmpty() }
            .map { Uri.decode(it) }

    /**
     * Resolve [decodedSegments] to an [SmbFile], or null if any segment is not
     * found. Empty segments resolve to the server root (share enumeration).
     */
    fun resolve(
        ctx: CIFSContext,
        host: String,
        port: Int,
        decodedSegments: List<String>,
    ): SmbFile? {
        android.util.Log.d(TAG, "resolve: host=$host, port=$port, segments=$decodedSegments")
        // Reject path-traversal segments: ".." would climb out of the share root and
        // could expose admin shares ($ shares); "." is a no-op that must never reach here.
        if (decodedSegments.any { it == ".." || it == "." }) {
            android.util.Log.w(TAG, "resolve: rejected path containing traversal segments (.. or .)")
            return null
        }
        if (decodedSegments.isEmpty()) {
            android.util.Log.d(TAG, "resolve: empty segments, returning host root smb://$host:$port/")
            return SmbFile("smb://$host:$port/", ctx)
        }

        val shareUrl = "smb://$host:$port/${Uri.encode(decodedSegments.first())}/"
        android.util.Log.d(TAG, "resolve: shareUrl=$shareUrl")
        var current = SmbFile(shareUrl, ctx)
        for (segment in decodedSegments.drop(1)) {
            val children = try {
                listChildren(current)
            } catch (e: SmbAuthException) {
                android.util.Log.w(TAG, "resolve: Access denied listing children of ${current.url} (SmbAuthException: ${e.message})")
                return null
            } catch (e: Exception) {
                android.util.Log.e(TAG, "resolve: failed to list children of ${current.url}", e)
                return null
            }
            val match = children[segment.lowercase()]
            if (match == null) {
                android.util.Log.w(
                    TAG,
                    "resolve failed: segment '$segment' not found in directory '${current.url}'. Available children: ${children.keys}"
                )
                return null
            }
            current = match
        }
        android.util.Log.d(TAG, "resolve success: ${current.url}")
        return current
    }

    /** Resolve the parent directory of [decodedSegments] (all but the last). */
    fun resolveParent(
        ctx: CIFSContext,
        host: String,
        port: Int,
        decodedSegments: List<String>,
    ): SmbFile? = resolve(ctx, host, port, decodedSegments.dropLast(1))

    /**
     * Cached name→child map for [dir]. Names are lower-cased and de-slashed.
     *
     * The cache key includes the identity of the directory's [CIFSContext]: the
     * player, thumbnails, and browser each use a distinct pooled context, and the
     * returned [SmbFile] objects are bound to the context that created them.
     * Keying by URL alone would let one subsystem receive another's context and
     * route I/O through the wrong pooled connection.
     */
    /** Clear all cached listings — call when pooled connections are released. */
    fun clearCache() { listingCache.clear() }

    private fun listChildren(dir: SmbFile): Map<String, SmbFile> {
        val ctx = dir.context
        val url = dir.url.toString()
        val ctxCache = listingCache.getOrPut(ctx) { java.util.concurrent.ConcurrentHashMap() }
        ctxCache[url]?.let {
            if (SystemClock.elapsedRealtime() - it.timestamp < TTL_MS) {
                android.util.Log.d(TAG, "listChildren: cache hit for $url")
                return it.byName
            }
        }
        android.util.Log.d(TAG, "listChildren: cache miss, fetching listFiles for $url")
        try {
            val files = dir.listFiles()
            val map = files
                ?.associateBy { it.name.trimEnd('/').lowercase() }
                ?: emptyMap()
            ctxCache[url] = CachedListing(SystemClock.elapsedRealtime(), map)
            android.util.Log.d(TAG, "listChildren: successfully fetched ${map.size} items for $url")
            return map
        } catch (e: SmbAuthException) {
            android.util.Log.w(TAG, "listChildren: Access denied listing children for $url (SmbAuthException: ${e.message})")
            throw e
        } catch (e: Exception) {
            android.util.Log.e(TAG, "listChildren: failed listing children for $url", e)
            throw e
        }
    }
}

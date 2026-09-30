package com.rhnxdev.hzplayer.core.thumbnail

import java.io.File
import java.util.concurrent.TimeUnit

/**
 * Max age of a cached thumbnail (or fail marker) before it is pruned on launch.
 * The `video_thumbs` folder is otherwise unbounded — this keeps it self-cleaning.
 */
val THUMB_MAX_AGE_MS: Long = TimeUnit.DAYS.toMillis(180)

/**
 * Delete cached thumbnails in [dir] not modified within [maxAgeMs].
 *
 * Covers both `.webp` frames and `.f2.fail` markers. Per-file IO errors are
 * swallowed so one locked/undeletable file never aborts the pass, and a
 * missing/empty dir is a safe no-op. Only touches [dir]; never Coil's own
 * `image_cache`.
 *
 * @param dir the thumbnail cache directory (e.g. cacheDir/video_thumbs)
 * @param maxAgeMs delete files whose last-modified age exceeds this
 * @param now current time in ms — injectable for testing
 * @return number of files deleted
 */
fun pruneOldThumbnails(
    dir: File,
    maxAgeMs: Long,
    now: Long = System.currentTimeMillis(),
): Int {
    val files = dir.takeIf { it.isDirectory }?.listFiles() ?: return 0
    var deleted = 0
    for (file in files) {
        if (!file.isFile) continue
        val age = now - file.lastModified()
        if (age > maxAgeMs) {
            val ok = runCatching { file.delete() }.getOrDefault(false)
            if (ok) deleted++
        }
    }
    return deleted
}

package com.rhnxdev.hzplayer.core.thumbnail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.os.Build
import android.util.Log
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver
import jcifs.smb.SmbFile
import jcifs.smb.SmbRandomAccessFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toPath
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL
import okhttp3.Credentials
import okhttp3.Request
import java.util.concurrent.TimeUnit

/** Coil model: a video file whose frame we want as a thumbnail. */
data class VideoFrame(val path: String, val dateModified: Long)

/** Frame timestamp at 40% of the clip, in microseconds (MediaMetadataRetriever unit). */
fun frameTimeUs(durationMs: Long): Long = durationMs * 1000L * 40 / 100

/** Longest edge of the cached thumbnail; keeps WebP files small. */
private const val THUMB_MAX_WIDTH = 720

/** Smaller target for network (SMB) thumbnails — less decode/transfer work. */
private const val THUMB_MAX_WIDTH_NETWORK = 480

/** How many bytes to download from a remote video for thumbnail extraction. */
private const val REMOTE_HEAD_BYTES = 512_000L // 512 KB — enough for the moov atom; smaller range = faster thumbnail fetch on slow links

/** Memory-cache key — path + mtime so an edited file gets a fresh frame. */
class VideoFrameKeyer : Keyer<VideoFrame> {
    override fun key(data: VideoFrame, options: Options): String =
        "${data.path}:${data.dateModified}"
}

private val THUMBNAIL_SEMAPHORE = Semaphore(permits = 3)

/**
 * Generates (and disk-caches as WebP) a frame ~40% into a video.
 */
class VideoFrameFetcher(
    private val data: VideoFrame,
    private val options: Options,
) : Fetcher {

    override suspend fun fetch(): FetchResult? = withContext(Dispatchers.IO) {
        val cacheFile = cacheFileFor(data)

        // ── Fast path: thumbnail already on disk ──────────────────────────
        // Skip the semaphore entirely for cache hits so scrolling through a
        // folder of already-extracted videos isn't gated behind the 3-permit
        // extraction concurrency cap.  A single file-existence check + ImageSource
        // creation is ~0 ms compared to extraction.
        if (cacheFile.exists()) {
            return@withContext SourceFetchResult(
                source = ImageSource(
                    file = cacheFile.absolutePath.toPath(),
                    fileSystem = FileSystem.SYSTEM,
                ),
                mimeType = "image/webp",
                dataSource = DataSource.DISK,
            )
        }

        // ── Slow path: extract a new frame ────────────────────────────────
        val failMarker = failMarkerFor(data)

        // Skip re-running the (often expensive SMB/remote) pipeline when a
        // recent attempt already failed — Coil would otherwise re-decode on
        // every view. Marker TTL = 1 day.
        if (failMarker.exists() && isFailMarkerFresh(failMarker)) {
            return@withContext null
        }

        val bitmap = THUMBNAIL_SEMAPHORE.withPermit {
            extractFrame()
        }
        if (bitmap == null) {
            try { failMarker.createNewFile() } catch (_: Exception) {}
            return@withContext null
        }
        try {
            cacheFile.parentFile?.mkdirs()
            cacheFile.outputStream().use { out ->
                bitmap.compress(webpFormat(), 75, out)
            }
        } finally {
            bitmap.recycle()
        }

        if (!cacheFile.exists()) {
            return@withContext null
        }

        return@withContext SourceFetchResult(
            source = ImageSource(
                file = cacheFile.absolutePath.toPath(),
                fileSystem = FileSystem.SYSTEM,
            ),
            mimeType = "image/webp",
            dataSource = DataSource.DISK,
        )
    }

    private fun extractFrame(): Bitmap? {
        val uri = data.path
        val scheme = uri.substringBefore("://").lowercase()

        val result = when {
            scheme == "smb" -> extractSmbFrame(uri)
            scheme in setOf("ftp", "sftp", "webdav", "webdavs", "http", "https") -> extractRemoteFrame(uri)
            else -> extractLocalFrame(uri)
        }
        return result
    }

    private fun extractSmbFrame(remoteUri: String): Bitmap? {
        val androidUri = Uri.parse(remoteUri)
        val username = Uri.decode(androidUri.userInfo?.substringBefore(':') ?: "")
        val password = Uri.decode(androidUri.userInfo?.substringAfter(':', "") ?: "")
        val host = androidUri.host ?: run {
            return null
        }
        val port = androidUri.port.takeIf { it > 0 } ?: 445
        
        // Resolve the target by walking the directory tree via listFiles() rather
        // than constructing an SmbFile from a URL containing the path. jcifs
        // mis-handles %-encoded segments (spaces → "file not found", emoji /
        // fullwidth CJK → "syntax incorrect"). See [SmbPathResolver], which also
        // caches directory listings so a burst of thumbnails in one folder shares
        // a single listFiles() round-trip.
        val segments = SmbPathResolver.decodedSegmentsOf(androidUri.encodedPath)
        if (segments.isEmpty()) {
            Log.w(TAG, "extractSmbFrame: no path in $remoteUri"); return null
        }

        return try {
            val ctx = ConnectionPool.borrowSmbThumbnailContext(host, port, username, password)
            try {
                val file = SmbPathResolver.resolve(ctx, host, port, segments) ?: run {
                    Log.w(TAG, "extractSmbFrame: file not found: $remoteUri"); return null
                }
                val size = file.length()
                val bridge = RandomAccessBridge(file, size, lightweight = true)
                try {
                    NativeThumbnailExtractor.extractThumbnail(
                        bridge, 0.40f, THUMB_MAX_WIDTH_NETWORK, fastMode = true
                    )
                } finally {
                    bridge.close()
                }
            } finally {
                ConnectionPool.returnSmbThumbnailContext(host, port, username, password)
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractSmbFrame: failed", e)
            null
        }
    }

    private fun extractRemoteFrame(remoteUri: String): Bitmap? {
        val tempFile = try {
            val f = File(options.context.cacheDir, "thumb_temp_${data.path.hashCode()}.tmp")
            downloadHead(remoteUri, f, REMOTE_HEAD_BYTES)
            if (f.exists() && f.length() > 0) {
                f
            } else {
                null
            }
        } catch (e: Exception) {
            null
        } ?: return null

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                FileInputStream(tempFile).use { fis ->
                    retriever.setDataSource(fis.fd, 0L, tempFile.length())
                }
                val frame = extractBestFrame(retriever)
                frame
            } finally {
                runCatching { retriever.release() }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun extractLocalFrame(path: String): Bitmap? {
        // Native FFmpeg first: fast (100–700 ms), handles everything in the
        // user's library (h264/hevc/mpeg4/vp9/av1…), and never hangs.
        val frame = extractLocalFrameNative(path)
        if (frame != null) return frame

        // Fallback: MediaMetadataRetriever via Android's mediaserver.  Catches
        // exotic formats the FFmpeg build might lack (e.g. hardware-only DRM
        // content).  Bail early on *parse* failures (0x80000000) — retrying
        // with a different access method can't help and each retry burns ~2 s.
        return extractLocalFrameMmfr(path)
    }

    private fun extractLocalFrameMmfr(path: String): Bitmap? {
        val retriever = MediaMetadataRetriever()
        return try {
            // Try 1: Uri.fromFile → ContentResolver (works for FUSE mounts, SMB mounts)
            try {
                val fileUri = Uri.fromFile(File(path))
                retriever.setDataSource(options.context, fileUri)
            } catch (e1: Exception) {
                if (e1.isMmfrParseFailure()) throw e1
                // Try 2: FileInputStream with fd
                try {
                    val file = File(path)
                    FileInputStream(file).use { fis ->
                        retriever.setDataSource(fis.fd, 0L, file.length())
                    }
                } catch (e2: Exception) {
                    if (e2.isMmfrParseFailure()) throw e2
                    // Try 3: raw path string
                    retriever.setDataSource(path)
                }
            }
            extractBestFrame(retriever)
        } catch (e: Exception) {
            Log.w(TAG, "extractLocalFrameMmfr: failed: ${e.message}")
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    /** Native FFmpeg extractor — primary path for local files. */
    private fun extractLocalFrameNative(path: String): Bitmap? {
        return try {
            val bridge = LocalRandomAccessBridge(path)
            try {
                NativeThumbnailExtractor.extractThumbnail(bridge, 0.40f, THUMB_MAX_WIDTH)
            } finally {
                bridge.close()
            }
        } catch (e: Exception) {
            null
        }
    }

    companion object {
        private const val TAG = "HzPlayer/Thumb"
    }

    private fun extractBestFrame(retriever: MediaMetadataRetriever): Bitmap? {
        val durationMs = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)
            ?.toLongOrNull() ?: 0L
        val srcW = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)
            ?.toIntOrNull() ?: 0
        val srcH = retriever
            .extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)
            ?.toIntOrNull() ?: 0

        val (dstW, dstH) = scaledDimensions(srcW, srcH)

        var frame: Bitmap? = try {
            retriever.getScaledFrameAtTime(
                frameTimeUs(durationMs),
                MediaMetadataRetriever.OPTION_CLOSEST_SYNC,
                dstW, dstH,
            )
        } catch (_: Exception) { null }

        if (frame == null) {
            frame = try {
                retriever.getFrameAtTime(
                    frameTimeUs(durationMs),
                    MediaMetadataRetriever.OPTION_CLOSEST_SYNC
                )
            } catch (_: Exception) { null }
        }

        if (frame == null) {
            frame = try { retriever.frameAtTime } catch (_: Exception) { null }
        }

        if (frame != null && (frame.width != dstW || frame.height != dstH)) {
            val scaled = Bitmap.createScaledBitmap(frame, dstW, dstH, true)
            if (scaled != frame) frame.recycle()
            frame = scaled
        }
        return frame
    }

    private fun downloadHead(remoteUri: String, dest: File, maxBytes: Long) {
        val scheme = remoteUri.substringBefore("://").lowercase()
        when (scheme) {
            "smb" -> return // SMB requires per-server auth context — skip thumbnails for it
            "ftp", "sftp", "webdav", "webdavs", "http", "https" -> downloadHttpHead(remoteUri, dest, maxBytes)
        }
    }

    private fun downloadHttpHead(url: String, dest: File, maxBytes: Long) {
        val scheme = url.substringBefore("://").lowercase()
        val isWebDav = scheme == "webdav" || scheme == "webdavs"
        // Plain HTTP(S) remote thumbnails keep a lightweight direct connection.
        if (!isWebDav) {
            val httpUrl = url
                .replaceFirst("webdav://", "http://", ignoreCase = true)
                .replaceFirst("webdavs://", "https://", ignoreCase = true)
            val conn = URL(httpUrl).openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
            try {
                conn.connect()
                val stream = conn.inputStream ?: return
                stream.use { s ->
                    FileOutputStream(dest).use { out ->
                        val buf = ByteArray(8192)
                        var total = 0L
                        while (total < maxBytes) {
                            val n = s.read(buf, 0, buf.size.coerceAtMost((maxBytes - total).toInt()))
                            if (n < 0) break
                            out.write(buf, 0, n)
                            total += n
                        }
                    }
                }
            } finally {
                conn.disconnect()
            }
            return
        }

        // WebDAV(S): route through the pooled, auth-aware OkHttpClient so thumbnails
        // reuse the same connections as playback and actually work with credentials
        // (URL.openConnection can't emit the Authorization header and rejects the scheme).
        val uri = Uri.parse(url)
        val host = uri.host ?: return
        val port = uri.port.takeIf { it > 0 } ?: if (scheme == "webdavs") 443 else 80
        val userInfo = uri.userInfo ?: ""
        val parts = userInfo.split(":", limit = 2)
        val user = Uri.decode(parts.getOrNull(0) ?: "")
        val pass = Uri.decode(parts.getOrNull(1) ?: "")
        val httpUrl = url
            .replaceFirst("webdav://", "http://", ignoreCase = true)
            .replaceFirst("webdavs://", "https://", ignoreCase = true)
        val client = ConnectionPool.borrowWebDavClient(host, port, scheme == "webdavs", user, pass)
        try {
            val request = Request.Builder().url(httpUrl)
                .header("Range", "bytes=0-${maxBytes - 1}")
                .apply { if (user.isNotEmpty()) header("Authorization", Credentials.basic(user, pass)) }
                .build()
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) return
                val stream = resp.body?.byteStream() ?: return
                FileOutputStream(dest).use { out ->
                    val buf = ByteArray(8192)
                    var total = 0L
                    while (total < maxBytes) {
                        val n = stream.read(buf, 0, buf.size.coerceAtMost((maxBytes - total).toInt()))
                        if (n < 0) break
                        out.write(buf, 0, n)
                        total += n
                    }
                }
            }
        } finally {
            ConnectionPool.returnWebDavClient(host, port, scheme == "webdavs", user, pass)
        }
    }

    private fun cacheFileFor(frame: VideoFrame): File {
        val hash = (frame.path.hashCode().toLong() and 0xffffffffL).toString(16)
        val dir = File(options.context.cacheDir, "video_thumbs")
        return File(dir, "${hash}_${frame.dateModified}.webp")
    }

    // Suffix is versioned ("f2"): bump it whenever the extraction pipeline
    // changes materially so stale markers written by an older, buggier build
    // stop blocking retries (a marker suppresses re-extraction for a day).
    private fun failMarkerFor(frame: VideoFrame): File =
        File(cacheFileFor(frame).path + ".f2.fail")

    private fun isFailMarkerFresh(marker: File): Boolean {
        val age = System.currentTimeMillis() - marker.lastModified()
        return age < TimeUnit.DAYS.toMillis(1)
    }

    class Factory : Fetcher.Factory<VideoFrame> {
        override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoFrameFetcher(data, options)
    }
}

/**
 * True when mediaserver opened the file but couldn't parse/decode it
 * (`setDataSource failed: status = 0x...`).  Retrying with another access
 * method can't help; access failures (IO/permission/path) are NOT this.
 */
private fun Exception.isMmfrParseFailure(): Boolean =
    this is RuntimeException && message?.contains("setDataSource failed") == true

/** Fit within [THUMB_MAX_WIDTH] preserving aspect; default 16:9 when source size is unknown. */
private fun scaledDimensions(srcW: Int, srcH: Int): Pair<Int, Int> {
    if (srcW <= 0 || srcH <= 0) return THUMB_MAX_WIDTH to (THUMB_MAX_WIDTH * 9 / 16)
    if (srcW <= THUMB_MAX_WIDTH) return srcW to srcH
    val dstH = (srcH.toLong() * THUMB_MAX_WIDTH / srcW).toInt().coerceAtLeast(1)
    return THUMB_MAX_WIDTH to dstH
}

@Suppress("DEPRECATION")
private fun webpFormat(): Bitmap.CompressFormat =
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) Bitmap.CompressFormat.WEBP_LOSSY
    else Bitmap.CompressFormat.WEBP

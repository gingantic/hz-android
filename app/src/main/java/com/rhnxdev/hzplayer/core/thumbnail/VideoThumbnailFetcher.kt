package com.rhnxdev.hzplayer.core.thumbnail

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import coil3.ImageLoader
import coil3.decode.DataSource
import coil3.decode.ImageSource
import coil3.fetch.FetchResult
import coil3.fetch.Fetcher
import coil3.fetch.SourceFetchResult
import coil3.key.Keyer
import coil3.request.Options
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import okio.FileSystem
import okio.Path.Companion.toPath
import android.media.MediaDataSource
import android.net.Uri
import android.util.Log
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

/** Coil model: a video file whose frame we want as a thumbnail. */
data class VideoFrame(val path: String, val dateModified: Long)

/** Frame timestamp at 40% of the clip, in microseconds (MediaMetadataRetriever unit). */
fun frameTimeUs(durationMs: Long): Long = durationMs * 1000L * 40 / 100

/** Longest edge of the cached thumbnail; keeps WebP files small. */
private const val THUMB_MAX_WIDTH = 720

/** How many bytes to download from a remote video for thumbnail extraction. */
private const val REMOTE_HEAD_BYTES = 1_024_000L // 1 MB

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
        Log.d(TAG, "fetch: path=${data.path} cacheExists=${cacheFile.exists()}")

        if (!cacheFile.exists()) {
            val bitmap = THUMBNAIL_SEMAPHORE.withPermit {
                extractFrame()
            }
            if (bitmap == null) {
                Log.w(TAG, "fetch: extractFrame returned null for ${data.path}")
                return@withContext null
            }
            try {
                cacheFile.parentFile?.mkdirs()
                cacheFile.outputStream().use { out ->
                    bitmap.compress(webpFormat(), 75, out)
                }
                Log.d(TAG, "fetch: cached thumbnail to ${cacheFile.absolutePath} (${cacheFile.length()} bytes)")
            } finally {
                bitmap.recycle()
            }
        }
        if (!cacheFile.exists()) {
            Log.w(TAG, "fetch: cache file still missing after write")
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
        Log.d(TAG, "extractFrame: uri=$uri scheme=$scheme")

        val result = when {
            scheme == "smb" -> extractSmbFrame(uri)
            scheme in setOf("ftp", "sftp", "webdav", "webdavs", "http", "https") -> extractRemoteFrame(uri)
            else -> extractLocalFrame(uri)
        }
        Log.d(TAG, "extractFrame: result=${result != null} for $uri")
        return result
    }

    private fun extractSmbFrame(remoteUri: String): Bitmap? {
        Log.d(TAG, "extractSmbFrame: start $remoteUri")
        val retriever = MediaMetadataRetriever()
        var dataSource: SmbThumbnailDataSource? = null
        return try {
            dataSource = SmbThumbnailDataSource(remoteUri, options.context)
            retriever.setDataSource(dataSource as MediaDataSource)
            val frame = extractBestFrame(retriever)
            Log.d(TAG, "extractSmbFrame: frame=${frame != null} size=${frame?.width}x${frame?.height}")
            frame
        } catch (e: Exception) {
            Log.e(TAG, "extractSmbFrame: failed", e)
            null
        } finally {
            runCatching { dataSource?.close() }
            runCatching { retriever.release() }
        }
    }

    private fun extractRemoteFrame(remoteUri: String): Bitmap? {
        Log.d(TAG, "extractRemoteFrame: start $remoteUri")
        val tempFile = try {
            val f = File(options.context.cacheDir, "thumb_temp_${data.path.hashCode()}.tmp")
            downloadHead(remoteUri, f, REMOTE_HEAD_BYTES)
            if (f.exists() && f.length() > 0) {
                Log.d(TAG, "extractRemoteFrame: downloaded ${f.length()} bytes to $f")
                f
            } else {
                Log.w(TAG, "extractRemoteFrame: download produced empty file")
                null
            }
        } catch (e: Exception) {
            Log.e(TAG, "extractRemoteFrame: download failed", e)
            null
        } ?: return null

        return try {
            val retriever = MediaMetadataRetriever()
            try {
                FileInputStream(tempFile).use { fis ->
                    retriever.setDataSource(fis.fd, 0L, tempFile.length())
                }
                val frame = extractBestFrame(retriever)
                Log.d(TAG, "extractRemoteFrame: frame=${frame != null} size=${frame?.width}x${frame?.height}")
                frame
            } finally {
                runCatching { retriever.release() }
            }
        } finally {
            tempFile.delete()
        }
    }

    private fun extractLocalFrame(path: String): Bitmap? {
        Log.d(TAG, "extractLocalFrame: start $path")
        val retriever = MediaMetadataRetriever()
        return try {
            // Try 1: Uri.fromFile → ContentResolver (works for FUSE mounts, SMB mounts)
            try {
                val fileUri = Uri.fromFile(File(path))
                Log.d(TAG, "extractLocalFrame: trying Uri.fromFile: $fileUri")
                retriever.setDataSource(options.context, fileUri)
            } catch (e1: Exception) {
                Log.d(TAG, "extractLocalFrame: Uri.fromFile failed, trying path directly: ${e1.message}")
                // Try 2: FileInputStream with fd
                try {
                    val file = File(path)
                    FileInputStream(file).use { fis ->
                        retriever.setDataSource(fis.fd, 0L, file.length())
                    }
                } catch (e2: Exception) {
                    Log.d(TAG, "extractLocalFrame: fd failed, trying raw path: ${e2.message}")
                    // Try 3: raw path string
                    retriever.setDataSource(path)
                }
            }
            val frame = extractBestFrame(retriever)
            Log.d(TAG, "extractLocalFrame: frame=${frame != null} size=${frame?.width}x${frame?.height}")
            frame
        } catch (e: Exception) {
            Log.e(TAG, "extractLocalFrame: all attempts failed", e)
            null
        } finally {
            runCatching { retriever.release() }
        }
    }

    companion object {
        private const val TAG = "VideoThumbnail"
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
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 5000
        conn.readTimeout = 5000
        conn.setRequestProperty("Range", "bytes=0-${maxBytes - 1}")
        try {
            conn.connect()
            val stream = conn.inputStream ?: return
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
        } finally {
            conn.disconnect()
        }
    }

    private fun cacheFileFor(frame: VideoFrame): File {
        val hash = (frame.path.hashCode().toLong() and 0xffffffffL).toString(16)
        val dir = File(options.context.cacheDir, "video_thumbs")
        return File(dir, "${hash}_${frame.dateModified}.webp")
    }

    class Factory : Fetcher.Factory<VideoFrame> {
        override fun create(data: VideoFrame, options: Options, imageLoader: ImageLoader): Fetcher =
            VideoFrameFetcher(data, options)
    }
}

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

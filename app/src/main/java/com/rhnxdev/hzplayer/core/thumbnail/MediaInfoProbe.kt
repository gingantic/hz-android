package com.rhnxdev.hzplayer.core.thumbnail

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.rhnxdev.hzplayer.domain.model.ChapterInfo
import java.io.FileInputStream
import java.io.RandomAccessFile

/**
 * Probes a media file's container + codec metadata via the native FFmpeg
 * demuxer, bridging the source bytes through a [ThumbnailSource].
 *
 * Supports `content://` URIs, `file://` URIs, plain filesystem paths, and
 * `smb://` URIs. Remote protocols other than SMB are not probed (returns null).
 *
 * The returned map uses the keys produced by the native probe, e.g.
 * `format`, `video_codec`, `video_profile`, `video_fps`, `audio_codec`,
 * `audio_sample_rate`, `audio_channels`, `*_bitrate`, … Only keys the demuxer
 * could determine are present.
 */
object MediaInfoProbe {

    private const val TAG = "MediaInfoProbe"

    /** Bounded LRU cache so re-opening Properties for the same file is instant. */
    private val cache = android.util.LruCache<String, Map<String, String>>(100)

    /**
     * Probe [uriOrPath] on [Dispatchers.IO]. Returns null when the source can't
     * be opened or parsed, or when the native lib is unavailable.
     */
    suspend fun probe(context: Context, uriOrPath: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            val cachedResult = synchronized(cache) { cache.get(uriOrPath) }
            if (cachedResult != null) return@withContext cachedResult

            val result = try {
                withSource(context, uriOrPath) { bridge ->
                    NativeThumbnailExtractor.probeMediaInfo(bridge)
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe: failed for $uriOrPath: ${e.message}")
                null
            }

            if (result != null) {
                synchronized(cache) { cache.put(uriOrPath, result) }
            }
            result
        }

    /** Bounded LRU cache of probed chapter lists, keyed by URI/path. */
    private val chapterCache = android.util.LruCache<String, List<ChapterInfo>>(50)

    /**
     * Probe container chapters of [uriOrPath] on [Dispatchers.IO]. Returns an
     * empty list when the source has no chapters, can't be opened, or the
     * native lib is unavailable. Same scheme support as [probe].
     */
    suspend fun probeChapters(context: Context, uriOrPath: String): List<ChapterInfo> =
        withContext(Dispatchers.IO) {
            val cached = synchronized(chapterCache) { chapterCache.get(uriOrPath) }
            if (cached != null) return@withContext cached

            val result = try {
                withSource(context, uriOrPath) { bridge ->
                    NativeThumbnailExtractor.probeChapters(bridge)
                        ?.map { (start, end, title) -> ChapterInfo(start, end, title) }
                } ?: emptyList()
            } catch (e: Exception) {
                Log.w(TAG, "probeChapters: failed for $uriOrPath: ${e.message}")
                emptyList()
            }

            synchronized(chapterCache) { chapterCache.put(uriOrPath, result) }
            result
        }

    /**
     * Opens [uriOrPath] as a [ThumbnailSource] (scheme-dispatched: local path,
     * `file://`, `content://`, `smb://`) and runs [block] with it, closing the
     * source afterwards. Returns null for unsupported schemes.
     */
    private fun <T> withSource(
        context: Context,
        uriOrPath: String,
        block: (ThumbnailSource) -> T?,
    ): T? {
        val scheme = uriOrPath.substringBefore("://", "").lowercase()
        return when {
            scheme == "smb" -> withSmbSource(uriOrPath, block)
            scheme == "content" -> withContentSource(context, uriOrPath, block)
            scheme == "file" -> Uri.parse(uriOrPath).path?.let { withLocalSource(it, block) }
            scheme.isEmpty() -> withLocalSource(uriOrPath, block)
            else -> null // ftp/sftp/webdav/http(s): not probed
        }
    }

    /** Local filesystem path — read directly with a [RandomAccessFile]. */
    private fun <T> withLocalSource(path: String, block: (ThumbnailSource) -> T?): T? {
        val bridge = LocalRandomAccessBridge(path)
        return try {
            block(bridge)
        } finally {
            bridge.close()
        }
    }

    /** `content://` URI — open a seekable fd via the [android.content.ContentResolver]. */
    private fun <T> withContentSource(
        context: Context,
        uriString: String,
        block: (ThumbnailSource) -> T?,
    ): T? {
        val uri = Uri.parse(uriString)
        val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return try {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val size = if (pfd.statSize > 0) pfd.statSize
                       else runCatching { channel.size() }.getOrDefault(0L)
            val bridge = ChannelRandomAccessBridge(channel, size) { runCatching { pfd.close() } }
            try {
                block(bridge)
            } finally {
                bridge.close()
            }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            Log.w(TAG, "withContentSource failed: ${e.message}")
            null
        }
    }

    /**
     * `smb://` URI — borrow a pooled CIFS context, resolve the file, and read
     * through a lightweight [RandomAccessBridge]. The context stays borrowed for
     * the whole probe because the bridge reads lazily.
     */
    private fun <T> withSmbSource(remoteUri: String, block: (ThumbnailSource) -> T?): T? {
        val androidUri = Uri.parse(remoteUri)
        val username = Uri.decode(androidUri.userInfo?.substringBefore(':') ?: "")
        val password = Uri.decode(androidUri.userInfo?.substringAfter(':', "") ?: "")
        val host = androidUri.host ?: return null
        val port = androidUri.port.takeIf { it > 0 } ?: 445

        val segments = SmbPathResolver.decodedSegmentsOf(androidUri.encodedPath)
        if (segments.isEmpty()) return null

        return try {
            val ctx = ConnectionPool.borrowSmbThumbnailContext(host, port, username, password)
            try {
                val file = SmbPathResolver.resolve(ctx, host, port, segments) ?: return null
                val size = file.length()
                val bridge = RandomAccessBridge(file, size, lightweight = true)
                try {
                    block(bridge)
                } finally {
                    bridge.close()
                }
            } finally {
                ConnectionPool.returnSmbThumbnailContext(host, port, username, password)
            }
        } catch (e: Exception) {
            Log.w(TAG, "withSmbSource failed: ${e.message}")
            null
        }
    }
}

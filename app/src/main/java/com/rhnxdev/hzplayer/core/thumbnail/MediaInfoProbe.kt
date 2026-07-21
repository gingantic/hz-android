package com.rhnxdev.hzplayer.core.thumbnail

import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.util.Log
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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

    /** Simple in-memory cache so re-opening Properties for the same file is instant. */
    private val cache = java.util.concurrent.ConcurrentHashMap<String, Map<String, String>>()

    /**
     * Probe [uriOrPath] on [Dispatchers.IO]. Returns null when the source can't
     * be opened or parsed, or when the native lib is unavailable.
     */
    suspend fun probe(context: Context, uriOrPath: String): Map<String, String>? =
        withContext(Dispatchers.IO) {
            cache[uriOrPath]?.let { return@withContext it }
            val result = try {
                val scheme = uriOrPath.substringBefore("://", "").lowercase()
                when {
                    scheme == "smb" -> probeSmb(uriOrPath)
                    scheme == "content" -> probeContent(context, uriOrPath)
                    scheme == "file" -> probeLocal(Uri.parse(uriOrPath).path ?: return@withContext null)
                    scheme.isEmpty() -> probeLocal(uriOrPath)
                    else -> null // ftp/sftp/webdav/http(s): not probed
                }
            } catch (e: Exception) {
                Log.w(TAG, "probe failed for $uriOrPath: ${e.message}")
                null
            }
            result?.also { cache[uriOrPath] = it }
        }

    /** Local filesystem path — read directly with a [RandomAccessFile]. */
    private fun probeLocal(path: String): Map<String, String>? {
        val bridge = LocalRandomAccessBridge(path)
        return try {
            NativeThumbnailExtractor.probeMediaInfo(bridge)
        } finally {
            bridge.close()
        }
    }

    /** `content://` URI — open a seekable fd via the [android.content.ContentResolver]. */
    private fun probeContent(context: Context, uriString: String): Map<String, String>? {
        val uri = Uri.parse(uriString)
        val pfd: ParcelFileDescriptor =
            context.contentResolver.openFileDescriptor(uri, "r") ?: return null
        return try {
            val channel = FileInputStream(pfd.fileDescriptor).channel
            val size = if (pfd.statSize > 0) pfd.statSize
                       else runCatching { channel.size() }.getOrDefault(0L)
            val bridge = ChannelRandomAccessBridge(channel, size) { runCatching { pfd.close() } }
            try {
                NativeThumbnailExtractor.probeMediaInfo(bridge)
            } finally {
                bridge.close()
            }
        } catch (e: Exception) {
            runCatching { pfd.close() }
            Log.w(TAG, "probeContent failed: ${e.message}")
            null
        }
    }

    /**
     * `smb://` URI — borrow a pooled CIFS context, resolve the file, and read
     * through a lightweight [RandomAccessBridge]. The context stays borrowed for
     * the whole probe because the bridge reads lazily.
     */
    private fun probeSmb(remoteUri: String): Map<String, String>? {
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
                    NativeThumbnailExtractor.probeMediaInfo(bridge)
                } finally {
                    bridge.close()
                }
            } finally {
                ConnectionPool.returnSmbThumbnailContext(host, port, username, password)
            }
        } catch (e: Exception) {
            Log.w(TAG, "probeSmb failed: ${e.message}")
            null
        }
    }
}

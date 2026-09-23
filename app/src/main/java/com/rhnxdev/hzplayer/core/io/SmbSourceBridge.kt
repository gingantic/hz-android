package com.rhnxdev.hzplayer.core.io

import android.net.Uri
import android.util.Log
import com.rhnxdev.hzplayer.core.util.userInfoPair
import com.rhnxdev.hzplayer.data.datasource.player.ConnectionPool
import com.rhnxdev.hzplayer.data.datasource.player.SmbPathResolver

/**
 * `smb://` URI — borrow a pooled CIFS context, resolve the file, and hand a
 * lightweight [SmbRandomAccessSource] to [block]. The context stays borrowed for
 * the whole call because the bridge reads lazily.
 *
 * Returns null when the URI carries no host or path, or the file cannot be
 * resolved. [logTag] attributes the warnings to the calling component.
 */
internal fun <T> withSmbSource(
    remoteUri: String,
    logTag: String,
    block: (RandomAccessMediaSource) -> T?,
): T? {
    val androidUri = Uri.parse(remoteUri)
    val (username, password) = androidUri.userInfoPair()
    val host = androidUri.host ?: return null
    val port = androidUri.port.takeIf { it > 0 } ?: 445

    val segments = SmbPathResolver.decodedSegmentsOf(androidUri.encodedPath)
    if (segments.isEmpty()) {
        Log.w(logTag, "no path in $remoteUri")
        return null
    }

    return try {
        val ctx = ConnectionPool.borrowSmbThumbnailContext(host, port, username, password)
        try {
            val file = SmbPathResolver.resolve(ctx, host, port, segments) ?: run {
                Log.w(logTag, "file not found: $remoteUri")
                return null
            }
            val size = file.length()
            val bridge = SmbRandomAccessSource(file, size, lightweight = true)
            try {
                block(bridge)
            } finally {
                bridge.close()
            }
        } finally {
            ConnectionPool.returnSmbThumbnailContext(host, port, username, password)
        }
    } catch (e: Exception) {
        Log.w(logTag, "withSmbSource failed: ${e.message}")
        null
    }
}

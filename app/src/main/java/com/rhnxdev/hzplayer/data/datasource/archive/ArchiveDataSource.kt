package com.rhnxdev.hzplayer.data.datasource.archive

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.rhnxdev.hzplayer.core.io.ArchiveRandomAccessSource
import com.rhnxdev.hzplayer.core.io.CloseableRandomAccessSource
import com.rhnxdev.hzplayer.core.util.ArchiveUri
import java.io.IOException

/**
 * Media3 [DataSource] that reads a single entry straight out of an archive
 * (zip/7z/rar/tar/iso/cab) via libarchive — no extraction to disk.
 *
 * Backed by a shared [ArchiveRandomAccessSource] so ExoPlayer's header/moov/Cues
 * extractor seeks (`close()` -> `open(newPosition)`) reuse the 16 MB decompressed
 * block cache, 2 MB pinned head/tail blocks, and open remote connection without
 * reconnecting or re-decompressing from byte 0.
 *
 * URI shape: [ArchiveUri] — `archive:///<urlEncContainer>/<urlEncEntry>`.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArchiveDataSource : DataSource {

    private val lock = Any()
    private var source: CloseableRandomAccessSource? = null
    private var readPosition: Long = 0L
    private var bytesRemaining: Long = C.LENGTH_UNSET.toLong()
    private var uri: Uri? = null
    private var scratchBuffer = ByteArray(0)

    override fun open(dataSpec: DataSpec): Long {
        synchronized(lock) {
            uri = dataSpec.uri
            val parsed = ArchiveUri.parse(dataSpec.uri.toString())
                ?: throw IOException("archive: malformed uri ${dataSpec.uri}")
            val (container, entry, password) = parsed
            val shared = ArchiveRandomAccessSource.acquireShared(container, entry, password)
            source = shared
            val totalLength = shared.getSize()
            if (totalLength > 0 && dataSpec.position > totalLength) {
                close()
                throw IOException("archive: position ${dataSpec.position} beyond size $totalLength")
            }
            readPosition = dataSpec.position
            bytesRemaining = when {
                dataSpec.length != C.LENGTH_UNSET.toLong() -> dataSpec.length
                totalLength > 0 -> (totalLength - dataSpec.position).coerceAtLeast(0L)
                else -> C.LENGTH_UNSET.toLong()
            }
            return bytesRemaining
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        synchronized(lock) {
            val activeSource = source ?: return -1
            if (bytesRemaining == 0L) return C.RESULT_END_OF_INPUT
            val toRead = if (bytesRemaining == C.LENGTH_UNSET.toLong()) {
                length
            } else {
                length.toLong().coerceAtMost(bytesRemaining).toInt()
            }
            if (toRead <= 0) return C.RESULT_END_OF_INPUT

            val n = if (offset == 0) {
                activeSource.readAt(readPosition, buffer, toRead)
            } else {
                if (scratchBuffer.size < toRead) {
                    scratchBuffer = ByteArray(toRead.coerceAtLeast(64 * 1024))
                }
                val got = activeSource.readAt(readPosition, scratchBuffer, toRead)
                if (got > 0) {
                    System.arraycopy(scratchBuffer, 0, buffer, offset, got)
                }
                got
            }
            if (n <= 0) return C.RESULT_END_OF_INPUT
            readPosition += n
            if (bytesRemaining != C.LENGTH_UNSET.toLong()) {
                bytesRemaining = (bytesRemaining - n).coerceAtLeast(0L)
            }
            return n
        }
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun close() {
        synchronized(lock) {
            uri = null
            readPosition = 0L
            bytesRemaining = C.LENGTH_UNSET.toLong()
            source?.let { s ->
                source = null
                runCatching { s.close() }
            }
        }
    }
}

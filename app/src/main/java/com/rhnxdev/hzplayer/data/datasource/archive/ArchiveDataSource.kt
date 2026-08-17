package com.rhnxdev.hzplayer.data.datasource.archive

import android.net.Uri
import androidx.media3.common.C
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.TransferListener
import com.rhnxdev.hzplayer.core.util.ArchiveUri
import java.io.IOException

/**
 * Media3 [DataSource] that reads a single entry straight out of an archive
 * (zip/7z/rar/tar/iso/cab) via libarchive — no extraction to disk.
 *
 * URI shape: [ArchiveUri] — `archive:///<urlEncContainer>/<urlEncEntry>`.
 */
@androidx.annotation.OptIn(androidx.media3.common.util.UnstableApi::class)
class ArchiveDataSource : DataSource {

    private var handle: Long = 0
    private var totalLength: Long = 0
    private var uri: Uri? = null

    override fun open(dataSpec: DataSpec): Long {
        uri = dataSpec.uri
        val parsed = ArchiveUri.parse(dataSpec.uri.toString())
            ?: throw IOException("archive: malformed uri ${dataSpec.uri}")
        val (container, entry, password) = parsed
        handle = ArchiveNative.nativeOpen(container, entry, password)
        if (handle == 0L) {
            throw IOException("archive: cannot open entry $entry in $container")
        }
        totalLength = ArchiveNative.nativeLength(handle)
        if (dataSpec.position > 0) {
            if (!ArchiveNative.nativeSeek(handle, dataSpec.position)) {
                throw IOException("archive: seek to ${dataSpec.position} failed")
            }
        }
        val remaining = totalLength - dataSpec.position
        return if (dataSpec.length == C.LENGTH_UNSET.toLong()) {
            remaining
        } else {
            dataSpec.length.coerceAtMost(remaining)
        }
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        val n = ArchiveNative.nativeRead(handle, buffer, offset, length)
        if (n < 0) throw IOException("archive: read error")
        if (n == 0) return C.RESULT_END_OF_INPUT
        return n
    }

    override fun getUri(): Uri? = uri

    override fun getResponseHeaders(): Map<String, List<String>> = emptyMap()

    override fun addTransferListener(transferListener: TransferListener) {}

    override fun close() {
        uri = null
        if (handle != 0L) {
            ArchiveNative.nativeClose(handle)
            handle = 0
        }
    }

    private companion object {
        const val TAG = "HzPlayer/Archive"
    }
}

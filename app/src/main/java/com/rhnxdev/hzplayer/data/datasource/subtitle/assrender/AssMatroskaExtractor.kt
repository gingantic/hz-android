package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorInput
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.PositionHolder
import androidx.media3.extractor.mkv.MatroskaExtractor

/**
 * Wraps [MatroskaExtractor] to:
 * 1. Pass [AssExtractorOutput] during `init` so subtitle tracks and SeekMaps
 *    are properly intercepted for libass and seekability
 * 2. Performs automatic cluster resynchronization (0x1F43B675) after seeking in MKV
 *    files without Cues, enabling instant and accurate seeking just like VLC/FFmpeg
 */
@UnstableApi
class AssMatroskaExtractor(
    private val handler: AssHandler,
) : Extractor {

    private val delegate = MatroskaExtractor(
        AssSubtitleParserFactory(),
        MatroskaExtractor.FLAG_EMIT_RAW_SUBTITLE_DATA,
    )

    private var assOutput: AssExtractorOutput? = null
    private var isLinearFallback = false
    private var pendingClusterSync = false
    private var firstClusterSeen = false

    override fun init(output: ExtractorOutput) {
        val wrapped = AssExtractorOutput(output, handler) { isFallback ->
            isLinearFallback = isFallback
        }
        assOutput = wrapped
        delegate.init(wrapped)
    }

    override fun sniff(input: ExtractorInput): Boolean = delegate.sniff(input)

    override fun read(input: ExtractorInput, seekPosition: PositionHolder): Int {
        val output = assOutput
        if (output != null) {
            if (input.length > 0 && output.streamLength <= 0) {
                output.streamLength = input.length
            }
            if (!firstClusterSeen && input.position > 0) {
                firstClusterSeen = true
                output.firstClusterPosition = input.position
            }
        }

        if (pendingClusterSync && isLinearFallback) {
            try {
                syncToCluster(input)
            } catch (_: Exception) {
                // Cluster sync is best-effort; if it fails just let the delegate try from
                // the current position — worst case we get a couple of corrupt frames.
            }
        }
        pendingClusterSync = false

        return try {
            delegate.read(input, seekPosition)
        } catch (e: IllegalStateException) {
            // "No valid varint length mask found" — the EBML parser landed on a
            // non-aligned position after a seek. Signal RESULT_CONTINUE so ExoPlayer
            // retries from the next position rather than crashing the entire session.
            android.util.Log.w("AssMatroskaExtractor",
                "EBML varint error at pos=${input.position}, skipping: ${e.message}")
            Extractor.RESULT_CONTINUE
        }
    }

    override fun seek(position: Long, timeUs: Long) {
        pendingClusterSync = isLinearFallback && position > 4096L
        delegate.seek(position, timeUs)
    }

    override fun release() = delegate.release()

    override fun getUnderlyingImplementation(): Extractor = delegate

    /**
     * Efficiently scans forward from the current input position to find the nearest Matroska
     * Cluster start marker (0x1F, 0x43, 0xB6, 0x75).
     * Positions [input] directly at the Cluster header.
     *
     * Important: only peek operations are used during the scan so the real read
     * position stays unchanged; a single [ExtractorInput.skipFully] call at the end
     * advances the stream to the found cluster.
     */
    private fun syncToCluster(input: ExtractorInput) {
        if (input.position < 4096L) return

        input.resetPeekPosition()

        // 1. Fast path: already at a cluster start
        val header = ByteArray(4)
        val atCluster = input.peekFully(header, 0, 4, true) &&
            header[0] == 0x1F.toByte() && header[1] == 0x43.toByte() &&
            header[2] == 0xB6.toByte() && header[3] == 0x75.toByte()
        input.resetPeekPosition()
        if (atCluster) return

        // 2. Sliding-window scan (peek only — real position stays put)
        val window = ByteArray(65536)
        var peekedTotal = 0
        val maxSearch = 1024 * 1024 // 1 MB limit

        while (peekedTotal < maxSearch) {
            val toRead = minOf(window.size, maxSearch - peekedTotal)
            if (toRead < 4) break
            if (!input.peekFully(window, 0, toRead, true)) break

            for (i in 0 until toRead - 3) {
                if (window[i]     == 0x1F.toByte() &&
                    window[i + 1] == 0x43.toByte() &&
                    window[i + 2] == 0xB6.toByte() &&
                    window[i + 3] == 0x75.toByte()
                ) {
                    // Found cluster. Reset peek, then advance the real read position
                    // by exactly (peekedTotal + i) bytes from where we started.
                    input.resetPeekPosition()
                    val skip = peekedTotal + i
                    if (skip > 0) input.skipFully(skip)
                    return
                }
            }

            // Advance the peek cursor for the next window (overlap by 3 to avoid
            // missing a marker that straddles a window boundary).
            val advance = toRead - 3
            peekedTotal += advance
            input.resetPeekPosition()
            input.advancePeekPosition(peekedTotal)
        }

        // Cluster not found within limit — leave the stream position unchanged.
        input.resetPeekPosition()
    }
}

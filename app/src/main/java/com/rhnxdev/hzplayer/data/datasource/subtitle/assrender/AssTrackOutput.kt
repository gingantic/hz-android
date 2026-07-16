package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.util.Log
import androidx.media3.common.DataReader
import androidx.media3.common.Format
import androidx.media3.common.util.ParsableByteArray
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.TrackOutput
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isAssFormat
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isSrtFormat
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isVttFormat
import java.io.ByteArrayOutputStream

/**
 * Implements [TrackOutput] directly (not ForwardingTrackOutput) to ensure
 * we capture ALL data before forwarding to the delegate.
 *
 * ASS/SSA tracks are eavesdropped verbatim; their raw bytes already contain
 * timing so [AssHandler] handles them. SRT/WebVTT tracks are eavesdropped too,
 * but ExoPlayer strips their `-->` timing before delivery, so we synthesize a
 * Dialogue line from the cue text using the stream [timeUs] as the start and a
 * fixed hold as the end (the extractor doesn't expose per-cue duration).
 */
@UnstableApi
internal class AssTrackOutput(
    private val delegate: TrackOutput,
    private val handler: AssHandler,
    private val trackId: Int,
) : TrackOutput {

    companion object {
        private const val TAG = "assrender"
    }

    private var isLibassTrack = false
    private var isAss = false
    private var isVtt = false
    private val pendingData = ByteArrayOutputStream()

    override fun format(format: Format) {
        val mimeType = format.sampleMimeType

        isAss = isAssFormat(format)
        isVtt = isVttFormat(format)
        isLibassTrack = isAss || isSrtFormat(format) || isVttFormat(format)

        if (isLibassTrack) {
            if (isAss && format.initializationData.isNotEmpty()) {
                for (data in format.initializationData) {
                    val preview = String(data, 0, minOf(50, data.size), Charsets.UTF_8)
                    if (preview.contains("[Script Info]") || preview.contains("ScriptType:")) {
                        handler.onTrackHeader(trackId, data, format)
                        break
                    }
                }
            } else {
                // SRT/WebVTT carry no header — seed libass with a minimal one so
                // synthesized Dialogue lines have a style to bind to.
                handler.onTrackHeader(trackId, SubtitleConverters.buildMinimalAssHeader(handler.getVideoWidth(), handler.getVideoHeight()), format)
            }
        }

        delegate.format(format)
    }

    override fun sampleData(
        data: ParsableByteArray,
        length: Int,
        sampleDataPart: Int,
    ) {
        if (isLibassTrack) {
            val pos = data.position
            val bytes = ByteArray(length)
            data.readBytes(bytes, 0, length)
            pendingData.write(bytes)
            data.setPosition(pos)
        }
        delegate.sampleData(data, length, sampleDataPart)
    }

    override fun sampleData(
        input: DataReader,
        length: Int,
        allowEndOfInput: Boolean,
        sampleDataPart: Int,
    ): Int {
        if (isLibassTrack) {
            val bytes = ByteArray(length)
            val bytesRead = input.read(bytes, 0, length)
            if (bytesRead > 0) {
                pendingData.write(bytes, 0, bytesRead)
                val pba = ParsableByteArray(bytes, bytesRead)
                delegate.sampleData(pba, bytesRead, sampleDataPart)
            }
            return bytesRead
        }
        return delegate.sampleData(input, length, allowEndOfInput, sampleDataPart)
    }

    override fun sampleMetadata(
        timeUs: Long,
        flags: Int,
        size: Int,
        offset: Int,
        cryptoData: TrackOutput.CryptoData?,
    ) {
        if (isLibassTrack && pendingData.size() > 0) {
            val rawBytes = pendingData.toByteArray()
            // Embedded cues carry their own `-->` timing, but in Matroska the
            // absolute position lives in the block timestamp (timeUs) — the
            // in-text SRT time is relative/garbage. convertEmbeddedCue emits an
            // MKV Dialogue line so onSubtitleSample anchors at timeUs.
            val toFeed = if (isAss) rawBytes
                         else SubtitleConverters.convertEmbeddedCue(rawBytes, isVtt)
            if (toFeed != null) handler.onSubtitleSample(trackId, timeUs, toFeed)
            pendingData.reset()
        }
        delegate.sampleMetadata(timeUs, flags, size, offset, cryptoData)
    }
}

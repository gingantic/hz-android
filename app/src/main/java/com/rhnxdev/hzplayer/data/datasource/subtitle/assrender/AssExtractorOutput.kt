package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.ExtractorOutput
import androidx.media3.extractor.SeekMap
import androidx.media3.extractor.SeekPoint
import androidx.media3.extractor.TrackOutput

/**
 * Wraps [ExtractorOutput] to:
 * 1. Intercept subtitle tracks with [AssTrackOutput]
 * 2. Ensure any unseekable [SeekMap] (e.g. MKVs without Cues or missing index)
 *    is replaced with a linear byte-interpolated SeekMap so ExoPlayer
 *    can perform random-access seeking just like VLC/FFmpeg
 */
@UnstableApi
internal class AssExtractorOutput(
    private val delegate: ExtractorOutput,
    private val handler: AssHandler,
    private val onSeekMapResolved: (isLinearFallback: Boolean) -> Unit = {},
) : ExtractorOutput by delegate {

    private var subtitleTrackCount = 0
    var streamLength: Long = C.LENGTH_UNSET.toLong()
    var firstClusterPosition: Long = 0L

    override fun track(id: Int, type: Int): TrackOutput {
        val trackOutput = delegate.track(id, type)

        return if (type == C.TRACK_TYPE_TEXT) {
            val trackId = subtitleTrackCount++
            AssTrackOutput(trackOutput, handler, trackId)
        } else {
            trackOutput
        }
    }

    override fun seekMap(seekMap: SeekMap) {
        if (!seekMap.isSeekable && seekMap.durationUs > 0 && seekMap.durationUs != C.TIME_UNSET) {
            onSeekMapResolved(true)
            val length = if (streamLength > 0) streamLength else C.LENGTH_UNSET.toLong()
            val startPos = if (firstClusterPosition > 0) firstClusterPosition else 4096L
            val seekable = object : SeekMap {
                override fun isSeekable(): Boolean = true
                override fun getDurationUs(): Long = seekMap.durationUs
                override fun getSeekPoints(timeUs: Long): SeekMap.SeekPoints {
                    val duration = seekMap.durationUs
                    if (length <= startPos || duration <= 0) {
                        return SeekMap.SeekPoints(SeekPoint(timeUs, startPos))
                    }
                    val clampedTime = timeUs.coerceIn(0L, duration)
                    val dataRange = length - startPos
                    val fraction = clampedTime.toDouble() / duration.toDouble()
                    val targetOffset = (startPos + (fraction * dataRange).toLong()).coerceIn(startPos, length - 1L)
                    return SeekMap.SeekPoints(SeekPoint(clampedTime, targetOffset))
                }
            }
            delegate.seekMap(seekable)
        } else {
            onSeekMapResolved(false)
            delegate.seekMap(seekMap)
        }
    }
}

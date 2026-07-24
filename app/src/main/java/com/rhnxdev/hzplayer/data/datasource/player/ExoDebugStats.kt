package com.rhnxdev.hzplayer.data.datasource.player

import android.os.Build
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.rhnxdev.hzplayer.domain.model.DebugStats

/**
 * Encapsulates frame rate/dropped frame polling, hardware/software decoder
 * labeling, and runtime playback stats extraction for ExoPlayer.
 */
@OptIn(UnstableApi::class)
internal class ExoDebugStatsHelper {

    private var lastRenderedFrames: Long = 0
    private var lastFrameTimestamp: Long = 0L

    /** Compute instant rendered FPS from DecoderCounters. Call each polling interval. */
    fun pollRenderedFps(playerHolder: MediaPlayerHolder): Float {
        val (rendered, _) = playerHolder.readFrameCounters()
        val now = System.nanoTime()
        val fps = if (lastFrameTimestamp > 0 && lastRenderedFrames > 0) {
            val dt = (now - lastFrameTimestamp) / 1_000_000_000f
            val df = rendered - lastRenderedFrames
            if (dt > 0f && df >= 0) df / dt else 0f
        } else 0f
        lastRenderedFrames = rendered
        lastFrameTimestamp = now
        return fps
    }

    /** Get absolute dropped frame count. */
    fun pollDroppedFrames(playerHolder: MediaPlayerHolder): Long {
        val (_, dropped) = playerHolder.readFrameCounters()
        return dropped
    }

    /** Extract full runtime debug metrics for active video & audio tracks. */
    fun getDebugStats(player: Player, playerHolder: MediaPlayerHolder): DebugStats {
        val currentTracks = player.currentTracks
        var videoCodec = ""
        var videoCodecMime = ""
        var resolution = ""
        var videoBitrate = ""
        var frameRate = ""
        var colorInfo = ""
        var hdrInfo = ""

        var audioCodec = ""
        var audioCodecMime = ""
        var audioBitrate = ""
        var sampleRate = ""
        var channelCount = ""
        var audioLanguage = ""

        for (group in currentTracks.groups) {
            for (i in 0 until group.length) {
                if (!group.isTrackSelected(i)) continue
                val fmt = group.getTrackFormat(i)
                when (group.type) {
                    C.TRACK_TYPE_VIDEO -> {
                        if (videoCodec.isEmpty()) {
                            videoCodec = fmt.codecs ?: ""
                            videoCodecMime = fmt.sampleMimeType ?: ""
                            resolution = if (fmt.width > 0 && fmt.height > 0)
                                "${fmt.width}x${fmt.height}" else ""
                            videoBitrate = if (fmt.bitrate > 0) fmt.bitrate.toString() else ""
                            frameRate = if (fmt.frameRate > 0f) "${"%.2f".format(fmt.frameRate)} fps" else ""
                            val ci = fmt.colorInfo
                            if (ci != null) {
                                colorInfo = buildString {
                                    append(when (ci.colorSpace) {
                                        3 -> "BT.2020"
                                        7 -> "BT.709"
                                        else -> "BT.601"
                                    })
                                    append(" ")
                                    append(when (ci.colorTransfer) {
                                        6 -> "PQ"
                                        7 -> "HLG"
                                        else -> "SDR"
                                    })
                                    append(" ")
                                    append(when (ci.colorRange) {
                                        3 -> "FULL"
                                        else -> "LIMITED"
                                    })
                                }
                                if (ColorInfo.isTransferHdr(ci)) {
                                    hdrInfo = "HDR (${when (ci.colorTransfer) { 6 -> "PQ/ST.2084"; 7 -> "HLG"; else -> "yes" }})"
                                }
                            }
                        }
                    }
                    C.TRACK_TYPE_AUDIO -> {
                        if (audioCodec.isEmpty()) {
                            audioCodec = fmt.codecs ?: ""
                            audioCodecMime = fmt.sampleMimeType ?: ""
                            audioBitrate = if (fmt.bitrate > 0) fmt.bitrate.toString() else ""
                            sampleRate = if (fmt.sampleRate > 0) "${fmt.sampleRate} Hz" else ""
                            channelCount = if (fmt.channelCount > 0) {
                                when (fmt.channelCount) {
                                    1 -> "Mono"
                                    2 -> "Stereo"
                                    6 -> "5.1"
                                    7 -> "6.1"
                                    8 -> "7.1"
                                    else -> "${fmt.channelCount} ch"
                                }
                            } else ""
                            audioLanguage = fmt.language ?: ""
                        }
                    }
                }
            }
        }

        val videoDec = playerHolder.videoDecoderName.value
        val audioDec = playerHolder.audioDecoderName.value
        val videoDecLabel = if (videoDec.isNotEmpty()) "{${labelHwSw(videoDec)}} $videoDec" else ""
        val audioDecLabel = if (audioDec.isNotEmpty()) "{${labelHwSw(audioDec)}} $audioDec" else ""
        val decoderLabel = buildString {
            append(videoDecLabel)
            if (videoDecLabel.isNotEmpty() && audioDecLabel.isNotEmpty()) append(" | ")
            append(audioDecLabel)
        }.ifEmpty { "" }

        val fps = pollRenderedFps(playerHolder)
        return DebugStats(
            videoCodec = videoCodec,
            videoCodecMime = videoCodecMime,
            resolution = resolution,
            videoBitrate = videoBitrate,
            frameRate = frameRate,
            renderedFps = if (fps > 0f) "${"%.0f".format(fps)} fps" else "",
            droppedFrames = pollDroppedFrames(playerHolder).let { if (it > 0) it.toString() else "" },
            colorInfo = colorInfo,
            hdrInfo = hdrInfo,
            decoderName = decoderLabel,
            videoDecoderLabel = videoDecLabel,
            audioDecoderLabel = audioDecLabel,
            audioCodec = audioCodec,
            audioCodecMime = audioCodecMime,
            audioBitrate = audioBitrate,
            sampleRate = sampleRate,
            channelCount = channelCount,
            audioLanguage = audioLanguage,
            deviceModel = Build.MODEL,
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
            soCInfo = if (Build.VERSION.SDK_INT >= 31) {
                "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
            } else {
                "${Build.HARDWARE}"
            }.ifBlank { "${Build.HARDWARE}" },
        )
    }

    /** Tag decoder as HW or SW by its registration name. */
    fun labelHwSw(decoderName: String): String = when {
        decoderName.startsWith("c2.android.") -> "SW"
        decoderName.startsWith("c2.") -> "HW"
        decoderName.startsWith("OMX.") && !decoderName.contains(".google.", ignoreCase = true) -> "HW"
        else -> "SW"
    }
}

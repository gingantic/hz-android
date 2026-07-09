package com.rhnxdev.hzplayer.domain.model

import androidx.compose.runtime.Immutable

@Immutable
data class DebugStats(
    // Video
    val videoCodec: String = "",
    val videoCodecMime: String = "",
    val resolution: String = "",
    val videoBitrate: String = "",
    val videoBitrateEstimated: String = "",
    val frameRate: String = "",
    val colorInfo: String = "",
    val hdrInfo: String = "",
    val decoderName: String = "",
    val decoderInfo: String = "",
    val videoDecoderLabel: String = "",
    val audioDecoderLabel: String = "",

    // Audio
    val audioCodec: String = "",
    val audioCodecMime: String = "",
    val audioBitrate: String = "",
    val audioBitrateEstimated: String = "",
    val sampleRate: String = "",
    val channelCount: String = "",
    val audioLanguage: String = "",

    // Buffer / network
    val renderedFps: String = "",
    val droppedFrames: String = "",
    val bufferedPct: Int = 0,
    val contentLength: String = "",
    val networkSpeed: String = "",
    val bytesDownloaded: String = "",

    // Device
    val deviceModel: String = "",
    val androidVersion: String = "",
    val soCInfo: String = "",

    val isVisible: Boolean = false,
)

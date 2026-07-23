package com.rhnxdev.hzplayer.browser.media

import java.util.UUID

enum class MediaType {
    VIDEO,
    AUDIO,
    STREAM_HLS,
    STREAM_DASH,
    OTHER
}

data class DetectedMediaQuality(
    val url: String,
    val resolution: String,
    val bitrate: Long = 0L,
    val label: String
)

data class DetectedMediaItem(
    val id: String = UUID.randomUUID().toString().take(8),
    val url: String,
    val masterUrl: String? = null,
    val pageUrl: String = "",
    val title: String = "",
    val mimeType: String = "",
    val mediaType: MediaType = MediaType.VIDEO,
    val extension: String = "",
    val contentLength: Long = -1L,
    val formattedSize: String = "Unknown / Stream",
    val qualityLabel: String? = null,
    val headers: Map<String, String> = emptyMap(),
    val detectedTokens: Map<String, String> = emptyMap(),
    val timestamp: Long = System.currentTimeMillis(),
    val subQualities: List<DetectedMediaQuality> = emptyList(),
    val childVariants: List<DetectedMediaItem> = emptyList(),
    val selectedQualityUrl: String? = null,
) {
    val isMasterStream: Boolean
        get() = subQualities.isNotEmpty() ||
                mediaType == MediaType.STREAM_HLS ||
                mediaType == MediaType.STREAM_DASH ||
                MediaStreamDecoder.isMasterStreamUrl(url, mimeType) ||
                masterUrl != null

    val playUrl: String
        get() = masterUrl ?: selectedQualityUrl ?: url

    val displayUrl: String
        get() = playUrl

    val displayQuality: String
        get() = when {
            subQualities.isNotEmpty() -> "MASTER (${subQualities.size} Qualities)"
            isMasterStream -> "MASTER STREAM"
            !qualityLabel.isNullOrBlank() -> qualityLabel
            mediaType == MediaType.STREAM_HLS -> "HLS Stream"
            mediaType == MediaType.STREAM_DASH -> "DASH Stream"
            else -> extension.uppercase().ifBlank { "MEDIA" }
        }

    val hasAuthInfo: Boolean
        get() = detectedTokens.isNotEmpty() || headers.keys.any {
            it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true)
        }
}

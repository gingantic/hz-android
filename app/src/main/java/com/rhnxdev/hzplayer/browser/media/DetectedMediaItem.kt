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
    val selectedQualityUrl: String? = null,
) {
    val displayUrl: String
        get() = selectedQualityUrl ?: url

    val displayQuality: String
        get() = when {
            !qualityLabel.isNullOrBlank() -> qualityLabel
            subQualities.isNotEmpty() -> "${subQualities.size} Qualities"
            mediaType == MediaType.STREAM_HLS -> "HLS Stream"
            mediaType == MediaType.STREAM_DASH -> "DASH Stream"
            else -> extension.uppercase().ifBlank { "MEDIA" }
        }

    val hasAuthInfo: Boolean
        get() = detectedTokens.isNotEmpty() || headers.keys.any {
            it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true)
        }
}

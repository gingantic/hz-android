package com.rhnxdev.hzplayer.domain.model

import androidx.compose.runtime.Immutable

/** Live network traffic stats for the active playback engine. */
@Immutable
data class NetworkTraffic(
    /** Total bytes downloaded since playback started. */
    val bytesDown: Long = 0,
    /** Current download speed in bytes/sec. */
    val speedDown: Long = 0,
) {
    companion object {
        val DEFAULT = NetworkTraffic()
    }
}

package com.rhnxdev.hzplayer.domain.model

import androidx.compose.runtime.Immutable

/** Live network traffic stats for the active playback engine. */
@Immutable
data class NetworkTraffic(
    /** Total bytes downloaded since playback started. */
    val bytesDown: Long = 0,
    /** Current download speed in bytes/sec. */
    val speedDown: Long = 0,
    /** True when the platform doesn't report per-UID traffic (`TrafficStats` == -1). */
    val unsupported: Boolean = false,
) {
    companion object {
        val DEFAULT = NetworkTraffic()
    }
}

package com.rhnxdev.hzplayer.domain.model

import androidx.compose.runtime.Immutable
import java.util.concurrent.atomic.AtomicLong

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

/**
 * Lock-free per-session byte counter incremented by remote transports
 * (`RemoteDataSourceBase`, `RemoteArchiveSource`, `SmbRandomAccessSource`,
 * `HttpRandomAccessSource`). Reset whenever a new playback session starts.
 */
object SessionNetworkTracker {
    private val sessionBytes = AtomicLong(0L)

    fun reset() {
        sessionBytes.set(0L)
    }

    fun onBytesRead(bytes: Long) {
        if (bytes > 0L) {
            sessionBytes.addAndGet(bytes)
        }
    }

    fun getBytesRead(): Long = sessionBytes.get()
}

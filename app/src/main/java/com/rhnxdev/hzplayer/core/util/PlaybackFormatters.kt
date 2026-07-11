package com.rhnxdev.hzplayer.core.util

/** Stateless formatting helpers for the debug/stats overlay. */

fun bitsToHuman(bitrateStr: String): String {
    val bps = bitrateStr.toLongOrNull() ?: return bitrateStr
    return formatBitsPerSecond(bps)
}

fun formatBitsPerSecond(bps: Long): String = when {
    bps < 1_000 -> "$bps bps"
    bps < 1_000_000 -> "${bps / 1000} kbps"
    else -> "${"%.2f".format(bps.toDouble() / 1_000_000)} Mbps"
}

fun formatDebugSpeed(bytesPerSec: Long): String = when {
    bytesPerSec < 1024 -> "$bytesPerSec B/s"
    bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
    else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
}

fun formatDebugBytes(bytes: Long): String = when {
    bytes < 1024 -> "$bytes B"
    bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
    bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
    else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
}

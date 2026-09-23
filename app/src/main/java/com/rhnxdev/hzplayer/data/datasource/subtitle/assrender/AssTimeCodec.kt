package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

/**
 * ASS timestamp codec. Both variants carry centiseconds and differ only in the
 * separator: standard ASS uses `H:MM:SS.CC`, the MKV chunk form `H:MM:SS:CC`.
 */
internal object AssTimeCodec {

    /** Parse standard `H:MM:SS.CC` → milliseconds; 0 when there is no fraction. */
    fun parseStandard(time: String): Long {
        val dotIdx = time.lastIndexOf('.')
        if (dotIdx < 0) return 0L
        val cs = time.substring(dotIdx + 1).toLongOrNull() ?: 0L
        val hms = time.substring(0, dotIdx).split(":")
        return millis(
            hms.getOrNull(0)?.toLongOrNull() ?: 0L,
            hms.getOrNull(1)?.toLongOrNull() ?: 0L,
            hms.getOrNull(2)?.toLongOrNull() ?: 0L,
            cs,
        )
    }

    /** Parse MKV `H:MM:SS:CC` → milliseconds; 0 unless there are exactly four fields. */
    fun parseMkv(time: String): Long {
        val parts = time.split(":")
        if (parts.size != 4) return 0L
        return millis(
            parts[0].toLongOrNull() ?: 0L,
            parts[1].toLongOrNull() ?: 0L,
            parts[2].toLongOrNull() ?: 0L,
            parts[3].toLongOrNull() ?: 0L,
        )
    }

    /** Milliseconds → `H:MM:SS<separator>CC`. Negative input clamps to zero. */
    fun format(ms: Long, separator: Char): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1000L
        val cs = (clamped % 1000L) / 10L
        return "%d:%02d:%02d%c%02d".format(h, m, s, separator, cs)
    }

    private fun millis(h: Long, m: Long, s: Long, cs: Long): Long =
        h * 3_600_000L + m * 60_000L + s * 1_000L + cs * 10L
}

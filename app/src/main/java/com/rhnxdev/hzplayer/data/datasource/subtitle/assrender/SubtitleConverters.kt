package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.MimeTypes

/**
 * Converts non-ASS subtitle formats (SRT, WebVTT) into ASS so they can be fed
 * to libass through the existing [AssHandler] pipeline. libass only parses
 * ASS/SSA, so any other format must be transformed before rendering.
 *
 * Conversion is intentionally simple: a single `Default` style, bottom-centre
 * positioning, basic `<i>/<b>/<u>` → ASS override-code mapping. Real ASS files
 * bypass this entirely (see [isLibassSubtitleFormat]).
 */
object SubtitleConverters {

    private const val DEFAULT_STYLE_NAME = "HzDefault"
    /** Subtitle font size as a fraction of PlayResY — keeps on-screen size constant across resolutions. */
    private const val FONT_SIZE_FRACTION = 0.07f
    /** Bottom margin (MarginV) as a fraction of PlayResY — lifts subs off the very edge. */
    private const val MARGIN_FRACTION = 0.06f

    /** Build the single `Default` style line, sizing font + bottom margin from [playResY]. */
    private fun defaultStyleLine(playResY: Int): String {
        val fontSize = (playResY * FONT_SIZE_FRACTION).toInt().coerceAtLeast(12)
        val marginV = (playResY * MARGIN_FRACTION).toInt().coerceAtLeast(8)
        return "Style: $DEFAULT_STYLE_NAME,sans-serif,$fontSize,&H00FFFFFF,&H000000FF,&H00000000,&H64000000,0,0,0,0,100,100,0,0,1,2,0,2,10,10,$marginV,1"
    }

    /** True for formats we can transform into ASS (SRT, VTT). */
    fun isConvertibleSubtitleFormat(mime: String?): Boolean {
        val m = mime?.lowercase() ?: return false
        return m == MimeTypes.APPLICATION_SUBRIP ||
            m == "text/x-subrip" ||
            m == MimeTypes.TEXT_VTT ||
            m.startsWith("text/vtt")
    }

    /** Pick a converter by MIME type. Returns null for unknown formats. */
    fun convertToAss(data: ByteArray, mime: String?, playResX: Int = 1920, playResY: Int = 1080): ByteArray? {
        val text = String(data, Charsets.UTF_8)
        val m = mime?.lowercase() ?: ""
        return when {
            m == MimeTypes.TEXT_VTT || m.startsWith("text/vtt") -> vttToAss(text, playResX, playResY).toByteArray(Charsets.UTF_8)
            // SRT and best-effort .sub (MicroDVD has no reliable timestamp; only
            // treat as SRT if it contains a "-->" timing line, else built-in wins).
            m == MimeTypes.APPLICATION_SUBRIP ||
                m == "text/x-subrip" ||
                (m == MimeTypes.APPLICATION_SUBRIP && text.contains("-->")) -> srtToAss(text, playResX, playResY).toByteArray(Charsets.UTF_8)
            // .sub / unknown: try SRT if it looks like one, else null → fallback.
            text.contains("-->") -> srtToAss(text, playResX, playResY).toByteArray(Charsets.UTF_8)
            else -> null
        }
    }

    // ── External (full ASS document) ──

    fun srtToAss(srt: String, playResX: Int = 1920, playResY: Int = 1080): String {
        val cues = parseSrtCues(srt)
        return buildAssDocument(cues, playResX, playResY)
    }

    fun vttToAss(vtt: String, playResX: Int = 1920, playResY: Int = 1080): String {
        val cues = parseVttCues(vtt)
        return buildAssDocument(cues, playResX, playResY)
    }

    // ── Embedded (minimal header for libass codec-private) ──

    /** A valid [Script Info] + [V4+ Styles] section so libass can accept
     *  Dialogue lines from an embedded SRT/VTT track (which carries no header). */
    fun buildMinimalAssHeader(playResX: Int = 1920, playResY: Int = 1080): ByteArray = buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        // PlayRes must match the renderer's storage/frame size (video resolution)
        // or libass defaults PlayResY=288 and inflates Fontsize ~3.75x. Stamping
        // the real frame size makes Fontsize absolute (FontSize px on that frame).
        appendLine("PlayResX: $playResX")
        appendLine("PlayResY: $playResY")
        appendLine("WrapStyle: 0")
        appendLine("ScaledBorderAndShadow: yes")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        appendLine(defaultStyleLine(playResY))
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
    }.toByteArray(Charsets.UTF_8)

    // ── Embedded cue → Dialogue line ──

    /**
     * Convert one embedded SRT/VTT cue (raw bytes, which still carry their
     * `-->` timing line) into a single standard-ASS Dialogue line. Unlike
     * external files we don't have the whole document, but one cue's own text
     * contains its full start/end, so timing is exact — no fixed hold needed.
     * The returned bytes start with "Dialogue:" — what [AssHandler.onSubtitleSample] expects.
     */
    /**
     * Convert one embedded SRT/VTT cue (raw bytes, which still carry their
     * `-->` timing line) into a single MKV-style [Dialogue] line so
     * [AssHandler.onSubtitleSample] routes it through its MKV branch — that
     * branch anchors the cue at the block's absolute [timeUs] (the correct
     * position) and only borrows the cue's own Duration for length. We CANNOT
     * use the in-text SRT start/end as the on-screen position: in Matroska the
     * absolute timing lives in the block timestamp, and the SRT text time is
     * relative/garbage (e.g. always "00:00:00,000 --> 00:00:01,126" per cue).
     * So we emit Start=0, Duration=<real SRT delta>, and let libass place it
     * via timeUs.
     */
    fun convertEmbeddedCue(rawCue: ByteArray, isVtt: Boolean): ByteArray? {
        val text = String(rawCue, Charsets.UTF_8)
        // ExoPlayer's SubripParser sometimes strips the `-->` timing line before
        // delivery, leaving only the bare text lines. parseSrtCues would then find
        // no timing and return empty → we'd drop the cue and fall back to the
        // built-in renderer (which can render artifacts). Treat a timing-less,
        // non-empty block as a zero-duration cue so libass still draws it.
        val cue = (if (isVtt) parseVttCues(text) else parseSrtCues(text))
            .firstOrNull { it.text.isNotEmpty() }
            ?: run {
                val bare = text.lineSequence()
                    .map { it.trimEnd('\r') }
                    .filter { it.isNotBlank() }
                    .joinToString("\\N").let { mapHtmlTags(it) }
                if (bare.isEmpty()) return null
                Cue(0L, 0L, bare)
            }
        val durationMs = (cue.endMs - cue.startMs).coerceAtLeast(0L)
        // MKV Dialogue: Start,Duration,ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text
        // Duration is H:MM:SS:CC (colon form → onSubtitleSample takes MKV branch).
        val body = "0,${msToMkvTime(durationMs)},0,0,$DEFAULT_STYLE_NAME,,0,0,0,,${cue.text}"
        return "Dialogue: $body".toByteArray(Charsets.UTF_8)
    }

    // ── Parsing ──

    internal data class Cue(val startMs: Long, val endMs: Long, val text: String)

    private val SRT_TIMING = Regex("""(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})\s*-->\s*(\d{1,2}:\d{2}:\d{2})[,.](\d{1,3})""")
    private val VTT_TIMING = Regex("""(\d{1,2}:)?(\d{1,2}:\d{2})[.](\d{1,3})\s*-->\s*(\d{1,2}:)?(\d{1,2}:\d{2})[.](\d{1,3})""")

    private fun parseSrtCues(srt: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = srt.lineSequence().iterator()
        val buffer = mutableListOf<String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (line.isBlank()) {
                flushSrtBlock(buffer, cues)
                buffer.clear()
            } else {
                buffer.add(line)
            }
        }
        flushSrtBlock(buffer, cues)
        return cues
    }

    private fun flushSrtBlock(block: List<String>, out: MutableList<Cue>) {
        // Find the timing line; anything before it is the (optional) index.
        val timingIdx = block.indexOfFirst { SRT_TIMING.containsMatchIn(it) }
        if (timingIdx < 0) return
        val m = SRT_TIMING.find(block[timingIdx]) ?: return
        val start = hmsToMs(m.groupValues[1], m.groupValues[2])
        val end = hmsToMs(m.groupValues[3], m.groupValues[4])
        val text = block.drop(timingIdx + 1)
            .map { it.trimEnd('\r') }
            .joinToString("\\N").let { mapHtmlTags(it) }
        if (text.isNotEmpty()) out.add(Cue(start, end, text))
    }

    private fun parseVttCues(vtt: String): List<Cue> {
        val cues = mutableListOf<Cue>()
        val lines = vtt.lineSequence().iterator()
        val buffer = mutableListOf<String>()
        while (lines.hasNext()) {
            val line = lines.next()
            if (VTT_TIMING.containsMatchIn(line)) {
                // Cue timing line — consume following text until blank.
                val m = VTT_TIMING.find(line) ?: continue
                val start = vttTimeToMs(m)
                val end = vttTimeToMs(m, isEnd = true)
                while (lines.hasNext()) {
                    val next = lines.next()
                    if (next.isBlank()) break
                    buffer.add(next)
                }
                val text = buffer.map { it.trimEnd('\r') }
                    .joinToString("\\N").let { mapHtmlTags(it) }
                if (text.isNotEmpty()) cues.add(Cue(start, end, text))
                buffer.clear()
            }
            // Non-cue lines (WEBVTT / NOTE / STYLE / REGION) are skipped.
        }
        return cues
    }

    // ── Helpers ──

    private fun hmsToMs(hms: String, frac: String): Long {
        val parts = hms.split(":")
        val h = parts.getOrNull(0)?.toLongOrNull() ?: 0L
        val m = parts.getOrNull(1)?.toLongOrNull() ?: 0L
        val s = parts.getOrNull(2)?.toLongOrNull() ?: 0L
        val ms = frac.padEnd(3, '0').take(3).toLongOrNull() ?: 0L
        return h * 3_600_000L + m * 60_000L + s * 1000L + ms
    }

    /** Read start or end from a [VTT_TIMING] match. */
    private fun vttTimeToMs(m: MatchResult, isEnd: Boolean = false): Long {
        val hMs = if (isEnd) m.groupValues[4] else m.groupValues[1]
        val hms = if (isEnd) m.groupValues[5] else m.groupValues[2]
        val frac = if (isEnd) m.groupValues[6] else m.groupValues[3]
        val h = hMs.takeIf { it.isNotEmpty() }?.let { hmsToH(hMs) } ?: 0L
        return h + hmsToMs(hms, frac)
    }

    private fun hmsToH(hh: String): Long = (hh.removeSuffix(":").toLongOrNull() ?: 0L) * 3_600_000L

    private fun msToAssTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1000L
        val cs = (clamped % 1000L) / 10L
        return "%d:%02d:%02d.%02d".format(h, m, s, cs)
    }

    /** Inverse of [parseMkvAssTimeMs]: milliseconds → "H:MM:SS:CC" (colon form). */
    internal fun msToMkvTime(ms: Long): String {
        val clamped = ms.coerceAtLeast(0L)
        val h = clamped / 3_600_000L
        val m = (clamped % 3_600_000L) / 60_000L
        val s = (clamped % 60_000L) / 1000L
        val cs = (clamped % 1000L) / 10L
        return "%d:%02d:%02d:%02d".format(h, m, s, cs)
    }

    /**
     * Reconstruct a [Cue] from a stored MKV chunk body (ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text)
     * plus its start and duration. Used by [AssHandler.selectTrack] to rebuild full ASS docs.
     */
    internal fun buildCueFromBody(body: ByteArray, startMs: Long, durationMs: Long): Cue? {
        val text = String(body, Charsets.UTF_8)
        // MKV body: ReadOrder,Layer,Style,Name,ML,MR,MV,Effect,Text
        val f = text.split(",", limit = 9)
        if (f.size < 9) return null
        val rawText = f[8].trim()
        if (rawText.isEmpty()) return null
        return Cue(startMs, startMs + durationMs, rawText)
    }

    /** Map a handful of HTML-ish inline tags to ASS override codes. */
    private fun mapHtmlTags(text: String): String = text
        .replace("<i>", "{\\i1}")
        .replace("</i>", "{\\i0}")
        .replace("<b>", "{\\b1}")
        .replace("</b>", "{\\b0}")
        .replace("<u>", "{\\u1}")
        .replace("</u>", "{\\u0}")
        // Strip remaining unknown tags (e.g. <c.color>, <ruby>…</ruby>).
        .replace(Regex("<[^>]+>"), "")
        .replace("\n", "\\N")

    internal fun buildAssDocument(cues: List<Cue>, playResX: Int = 1920, playResY: Int = 1080): String = buildString {
        appendLine("[Script Info]")
        appendLine("ScriptType: v4.00+")
        // Match the renderer storage size so Fontsize is absolute (see buildMinimalAssHeader).
        appendLine("PlayResX: $playResX")
        appendLine("PlayResY: $playResY")
        appendLine("WrapStyle: 0")
        appendLine("ScaledBorderAndShadow: yes")
        appendLine()
        appendLine("[V4+ Styles]")
        appendLine("Format: Name, Fontname, Fontsize, PrimaryColour, SecondaryColour, OutlineColour, BackColour, Bold, Italic, Underline, StrikeOut, ScaleX, ScaleY, Spacing, Angle, BorderStyle, Outline, Shadow, Alignment, MarginL, MarginR, MarginV, Encoding")
        appendLine(defaultStyleLine(playResY))
        appendLine()
        appendLine("[Events]")
        appendLine("Format: Layer, Start, End, Style, Name, MarginL, MarginR, MarginV, Effect, Text")
        for (cue in cues) {
            appendLine("Dialogue: 0,${msToAssTime(cue.startMs)},${msToAssTime(cue.endMs)},$DEFAULT_STYLE_NAME,,0,0,0,,${cue.text}")
        }
    }
}

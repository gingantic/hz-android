package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.Format
import androidx.media3.common.MimeTypes

/**
 * True if [mime] is (or looks like) an ASS/SSA subtitle MIME type.
 * `contains("ass"|"ssa")` keeps the broad match previously scattered across
 * call sites (e.g. "application/x-subtitle-ssa", "application/x-ass…").
 */
fun isAssMimeType(mime: String?): Boolean {
    val m = mime?.lowercase() ?: return false
    return m == MimeTypes.TEXT_SSA ||
        m == "text/x-ssa" ||
        m == "text/x-ass" ||
        m == "application/x-subtitle-ssa" ||
        m.contains("ass") ||
        m.contains("ssa")
}

/** True for SRT/WebVTT MIME strings (the convertible, non-ASS formats). */
fun isNonAssSubtitleMimeType(mime: String?): Boolean {
    val m = mime?.lowercase() ?: return false
    return m == MimeTypes.APPLICATION_SUBRIP ||
        m == "text/x-subrip" ||
        m == MimeTypes.TEXT_VTT ||
        m.startsWith("text/vtt")
}

/** True if [mime] is a subtitle format we route through libass (ASS, SRT, VTT). */
fun isLibassSubtitleMimeType(mime: String?): Boolean =
    isAssMimeType(mime) || isNonAssSubtitleMimeType(mime)

/**
 * True if [format] carries ASS/SSA subtitle data, by any signal ExoPlayer
 * exposes: MIME type, codec string, or codec-private [Format.initializationData]
 * (Matroska stores the [Script Info] header there instead of a clean MIME).
 */
fun isAssFormat(format: Format): Boolean {
    if (isAssMimeType(format.sampleMimeType)) return true
    val codecs = format.codecs?.lowercase() ?: ""
    if ("ass" in codecs || "ssa" in codecs) return true
    for (data in format.initializationData) {
        val preview = String(data, 0, minOf(50, data.size), Charsets.UTF_8)
        if (preview.contains("[Script Info]") || preview.contains("ScriptType:")) return true
    }
    return false
}

/** True if [format] is SRT (SubRip). Checks both sampleMimeType and the
 *  original codec string — since Media3 1.4, extracted subtitle tracks report
 *  sampleMimeType = application/x-media3-cues and move the real type to codecs. */
fun isSrtFormat(format: Format): Boolean {
    val m = format.sampleMimeType?.lowercase()
    if (m == MimeTypes.APPLICATION_SUBRIP || m == "text/x-subrip") return true
    val codecs = format.codecs?.lowercase() ?: return false
    return codecs == "srt" || codecs == "subrip" || codecs == MimeTypes.APPLICATION_SUBRIP
}

/** True if [format] is WebVTT. Same codec-aware check as [isSrtFormat]. */
fun isVttFormat(format: Format): Boolean {
    val m = format.sampleMimeType?.lowercase()
    if (m == MimeTypes.TEXT_VTT || m?.startsWith("text/vtt") == true) return true
    val codecs = format.codecs?.lowercase() ?: return false
    return codecs == "vtt" || codecs == "webvtt" || codecs == MimeTypes.TEXT_VTT
}

/**
 * True if [format] carries subtitle data we render through libass: ASS/SSA
 * directly, or SRT/WebVTT after conversion to ASS.
 */
fun isLibassSubtitleFormat(format: Format): Boolean {
    return isAssFormat(format) || isSrtFormat(format) || isVttFormat(format)
}

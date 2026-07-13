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

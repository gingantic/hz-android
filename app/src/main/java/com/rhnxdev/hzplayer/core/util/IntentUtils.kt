package com.rhnxdev.hzplayer.core.util

import android.content.Intent

/**
 * Parse HTTP headers from the conventions other apps (browsers, IPTV/players)
 * use to forward auth tokens with a media URL:
 *  - a `String[]` extra named `headers` of alternating key, value entries
 *    (the de-facto standard shared by VLC / MX Player / the ExoPlayer demo), and
 *  - a `Bundle` extra `android.media.intent.extra.HTTP_HEADERS` of string values.
 * Returns null when no headers are present.
 */
fun extractHttpHeaders(intent: Intent?): Map<String, String>? {
    if (intent == null) return null
    val out = LinkedHashMap<String, String>()
    intent.getStringArrayExtra("headers")?.let { arr ->
        var i = 0
        while (i + 1 < arr.size) {
            val key = arr[i]?.trim().orEmpty()
            val value = arr[i + 1] ?: ""
            if (key.isNotEmpty()) out[key] = value
            i += 2
        }
    }
    intent.getBundleExtra("android.media.intent.extra.HTTP_HEADERS")?.let { bundle ->
        for (key in bundle.keySet()) {
            val value = bundle.getString(key) ?: continue
            if (key.isNotBlank()) out[key] = value
        }
    }
    return if (out.isEmpty()) null else out
}

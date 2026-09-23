package com.rhnxdev.hzplayer.core.util

import android.content.Intent
import android.os.Bundle

/**
 * Extras the in-app browser sets when it hands a stream to a player activity.
 * Writer and reader must agree exactly — a mismatch is silent, because the extra
 * is simply never found and the default is used instead.
 */
const val EXTRA_MEDIA_TITLE = "extra_media_title"
const val EXTRA_PAGE_URL = "extra_page_url"
const val EXTRA_HEADERS_JSON = "extra_headers_json"
const val EXTRA_FROM_BROWSER = "from_browser"

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

/**
 * Writer counterpart of [extractHttpHeaders]: forward [headers] to a player
 * activity in all the shapes the players read — the alternating key/value
 * `headers` array, the `android.media.intent.extra.HTTP_HEADERS` bundle, and a
 * JSON copy. Entries with a blank key or value are dropped. No-op when nothing
 * survives that filter.
 */
fun Intent.putHttpHeaders(headers: Map<String, String>) {
    val pairs = mutableListOf<String>()
    val bundle = Bundle()
    headers.forEach { (k, v) ->
        if (k.isNotBlank() && v.isNotBlank()) {
            pairs.add(k)
            pairs.add(v)
            bundle.putString(k, v)
        }
    }
    if (pairs.isEmpty()) return
    putExtra("headers", pairs.toTypedArray())
    putExtra("android.media.intent.extra.HTTP_HEADERS", bundle)
    try {
        putExtra(EXTRA_HEADERS_JSON, org.json.JSONObject(headers as Map<*, *>).toString())
    } catch (_: Exception) {
    }
}

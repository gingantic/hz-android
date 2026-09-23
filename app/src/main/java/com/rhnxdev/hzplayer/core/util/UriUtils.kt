package com.rhnxdev.hzplayer.core.util

import android.net.Uri
import android.webkit.CookieManager

/**
 * Decoded `(username, password)` from a URI's user-info, both `""` when absent.
 * Splitting on the first `:` keeps a password that itself contains `:` intact.
 */
fun Uri.userInfoPair(): Pair<String, String> {
    val info = userInfo ?: return "" to ""
    return Uri.decode(info.substringBefore(':')) to Uri.decode(info.substringAfter(':', ""))
}

/**
 * Return a copy of this header map with the browser session's live cookies and a
 * page Referer added, so a URL handed to the player carries the CDN auth the
 * WebView already holds. A key already present — compared case-insensitively —
 * wins, because the sniffer's own header is more specific than the session jar.
 *
 * [cookieFallbackUrl] is the URL to ask the cookie jar about when [pageUrl] is
 * blank (e.g. fall back to the media URL itself).
 */
fun Map<String, String>.withLiveCookies(
    pageUrl: String,
    cookieFallbackUrl: String? = null,
): Map<String, String> {
    val merged = toMutableMap()
    if (merged.keys.none { it.equals("Cookie", ignoreCase = true) }) {
        val cookieUrl = pageUrl.ifBlank { cookieFallbackUrl.orEmpty() }
        if (cookieUrl.isNotBlank()) {
            val liveCookies = runCatching { CookieManager.getInstance().getCookie(cookieUrl) }.getOrNull()
            if (!liveCookies.isNullOrBlank()) merged["Cookie"] = liveCookies
        }
    }
    if (pageUrl.isNotBlank() && merged.keys.none { it.equals("Referer", ignoreCase = true) }) {
        merged["Referer"] = pageUrl
    }
    return merged
}

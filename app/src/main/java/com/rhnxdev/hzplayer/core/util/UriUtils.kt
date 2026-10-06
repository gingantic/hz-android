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
 * Adds the browser session's live cookies for [targetUrl] and a [refererUrl] so
 * a URL handed to the player carries the CDN auth the WebView already holds.
 * Cookies resolve for the target itself — never the page — so page-session
 * cookies can't leak to a third-party media host. An existing key (any case) wins.
 */
fun Map<String, String>.withLiveCookies(
    targetUrl: String,
    refererUrl: String = "",
): Map<String, String> {
    val merged = toMutableMap()
    val isHttp = targetUrl.startsWith("http://", ignoreCase = true) ||
        targetUrl.startsWith("https://", ignoreCase = true)
    if (isHttp && merged.keys.none { it.equals("Cookie", ignoreCase = true) }) {
        val liveCookies = runCatching { CookieManager.getInstance().getCookie(targetUrl) }.getOrNull()
        if (!liveCookies.isNullOrBlank()) merged["Cookie"] = liveCookies
    }
    if (refererUrl.isNotBlank() && merged.keys.none { it.equals("Referer", ignoreCase = true) }) {
        merged["Referer"] = refererUrl
    }
    return merged
}

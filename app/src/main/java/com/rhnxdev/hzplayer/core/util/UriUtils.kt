package com.rhnxdev.hzplayer.core.util

import android.net.Uri
import android.webkit.CookieManager

/**
 * Normalise a playback source string for the engine boundary.
 *
 * Local files travel through the app as raw absolute filesystem paths
 * (e.g. `/storage/emulated/0/Movies/my#clip.mp4`) rather than URIs. Handing such
 * a path straight to `Uri.parse` / Media3's `MediaItem.setUri(String)` mangles
 * any filename containing URI-reserved characters: `#` is read as a fragment and
 * `?` as a query (both truncate the path), `%` as a percent-escape, and a name
 * containing `://` can acquire a bogus scheme and route to the wrong DataSource.
 *
 * Wrapping a bare path in a correctly percent-encoded `file://` URI makes the
 * later `Uri.parse` lossless: Media3's `FileDataSource` and the native engine's
 * `file` branch both decode `uri.path` back to the original bytes.
 *
 * Only a **bare absolute path** (`/storage/…`) is wrapped — the shape the file
 * browser and media library emit, and the source of the real-world bug. Anything
 * that already carries a scheme is a real URI and is returned unchanged: a
 * `file://` URI from an external VIEW intent is already encoded by the launcher,
 * and re-encoding it here would double-encode its `%` escapes.
 *
 * @return a `file://` URI for a bare local path, otherwise [source] verbatim.
 */
fun normalizePlaybackUri(source: String): String {
    // Only bare absolute filesystem paths need wrapping. A string that already
    // carries a scheme (content://, smb://, http(s)://, archive://, file://) is a
    // real URI and must pass through untouched.
    if (!source.startsWith("/")) return source
    // Build an explicit file:/// URI: scheme "file", empty authority, the literal
    // path set via the builder so Android percent-encodes every reserved
    // character itself. Uri.parse().path then decodes it back losslessly for both
    // Media3's FileDataSource and the native engine's "file" branch.
    return runCatching {
        Uri.Builder()
            .scheme("file")
            .authority("")
            .path(source)
            .build()
            .toString()
    }.getOrDefault(source)
}

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

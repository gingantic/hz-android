package com.rhnxdev.hzplayer.browser

/**
 * All user-configurable browser settings.
 * Defaults mirror the current hard-coded behaviour in [TabManager].
 */
data class BrowserSettings(
    // ── JavaScript ──────────────────────────────────────────────
    val javaScriptEnabled: Boolean = true,
    val javaScriptCanOpenWindows: Boolean = false,

    // ── Privacy & Security ───────────────────────────────────────
    val adBlockEnabled: Boolean = true,
    val blockTrackersEnabled: Boolean = true,
    val cosmeticFilteringEnabled: Boolean = true,
    val blockCrossDomainPopups: Boolean = true,
    val cookiesEnabled: Boolean = true,
    val thirdPartyCookiesEnabled: Boolean = false,
    val blockMixedContent: Boolean = false,       // false = MIXED_CONTENT_ALWAYS_ALLOW (current)
    val safeBrowsingEnabled: Boolean = true,

    // ── User Agent ───────────────────────────────────────────────
    val userAgentMode: UserAgentMode = UserAgentMode.MOBILE,
    val customUserAgent: String = "",             // used when mode == CUSTOM

    // ── Content & Layout ────────────────────────────────────────
    val domStorageEnabled: Boolean = true,
    val mediaPlaybackRequiresGesture: Boolean = false,
    val loadImagesAutomatically: Boolean = true,
    val textZoom: Int = 100,                       // 50–200 %
    val useWideViewPort: Boolean = true,
    val loadWithOverviewMode: Boolean = true,

    // ── Zoom ─────────────────────────────────────────────────────
    val builtInZoomEnabled: Boolean = true,

    // ── Caching ──────────────────────────────────────────────────
    val cacheMode: BrowserCacheMode = BrowserCacheMode.NORMAL,

    // ── Session ──────────────────────────────────────────────────
    val restoreTabsOnStartup: Boolean = true,
)

enum class UserAgentMode(val label: String) {
    MOBILE("Mobile"),
    DESKTOP("Desktop"),
    CUSTOM("Custom"),
}

enum class BrowserCacheMode(val label: String) {
    NORMAL("Normal"),
    NO_CACHE("No Cache"),
    CACHE_ONLY("Cache Only"),
}

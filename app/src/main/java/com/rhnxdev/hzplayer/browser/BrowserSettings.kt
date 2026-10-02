package com.rhnxdev.hzplayer.browser

import androidx.annotation.StringRes
import com.rhnxdev.hzplayer.R

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
    val enabledFilterLists: Set<String> = setOf("easylist", "easyprivacy", "peter_lowe", "ublock_filters"),
    val customAdBlockRules: String = "",
    val lastAdBlockUpdateTimestamp: Long = 0L,
    val cookiesEnabled: Boolean = true,
    val thirdPartyCookiesEnabled: Boolean = false,
    val blockMixedContent: Boolean = true,        // true = MIXED_CONTENT_NEVER_ALLOW, matches Chrome's default since v79
    val safeBrowsingEnabled: Boolean = true,
    // intent:// links are the one external scheme malicious ads commonly abuse
    // to jump to another app / Play Store without a real user gesture, bypassing
    // pop-up blocking. tel:/mailto:/sms:/geo:/market: stay always-on — those are
    // expected, low-abuse browser behaviour standard on Chrome/Firefox/Brave.
    val allowIntentLinks: Boolean = true,

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

enum class UserAgentMode(@StringRes val labelRes: Int) {
    MOBILE(R.string.browser_ua_mobile),
    DESKTOP(R.string.browser_ua_desktop),
    CUSTOM(R.string.browser_ua_custom),
}

enum class BrowserCacheMode(@StringRes val labelRes: Int) {
    NORMAL(R.string.browser_cache_normal),
    NO_CACHE(R.string.browser_cache_no_cache),
    CACHE_ONLY(R.string.browser_cache_only),
}

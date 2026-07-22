package com.rhnxdev.hzplayer.browser

import android.content.Context
import android.content.SharedPreferences

/**
 * Persists [BrowserSettings] in SharedPreferences.
 * Obtain via [BrowserSettingsStore.get].
 */
class BrowserSettingsStore private constructor(prefs: SharedPreferences) {

    private val p = prefs

    fun load(): BrowserSettings = BrowserSettings(
        javaScriptEnabled          = p.getBoolean(KEY_JS, true),
        javaScriptCanOpenWindows   = p.getBoolean(KEY_JS_WINDOWS, false),
        adBlockEnabled             = p.getBoolean(KEY_ADBLOCK, true),
        blockTrackersEnabled       = p.getBoolean(KEY_TRACKERS, true),
        cosmeticFilteringEnabled   = p.getBoolean(KEY_COSMETIC, true),
        blockCrossDomainPopups     = p.getBoolean(KEY_CROSS_DOMAIN_POPUPS, true),
        cookiesEnabled             = p.getBoolean(KEY_COOKIES, true),
        thirdPartyCookiesEnabled   = p.getBoolean(KEY_3P_COOKIES, false),
        blockMixedContent          = p.getBoolean(KEY_BLOCK_MIXED, false),
        safeBrowsingEnabled        = p.getBoolean(KEY_SAFE_BROWSING, true),
        userAgentMode              = UserAgentMode.entries.find {
                                        it.name == p.getString(KEY_UA_MODE, null)
                                     } ?: UserAgentMode.MOBILE,
        customUserAgent            = p.getString(KEY_CUSTOM_UA, "") ?: "",
        domStorageEnabled          = p.getBoolean(KEY_DOM_STORAGE, true),
        mediaPlaybackRequiresGesture = p.getBoolean(KEY_MEDIA_GESTURE, false),
        loadImagesAutomatically    = p.getBoolean(KEY_IMAGES, true),
        textZoom                   = p.getInt(KEY_TEXT_ZOOM, 100),
        useWideViewPort            = p.getBoolean(KEY_WIDE_VIEWPORT, true),
        loadWithOverviewMode       = p.getBoolean(KEY_OVERVIEW, true),
        builtInZoomEnabled         = p.getBoolean(KEY_ZOOM, true),
        cacheMode                  = BrowserCacheMode.entries.find {
                                        it.name == p.getString(KEY_CACHE, null)
                                     } ?: BrowserCacheMode.NORMAL,
        restoreTabsOnStartup       = p.getBoolean(KEY_RESTORE_TABS, true),
    )

    fun save(s: BrowserSettings) {
        p.edit()
            .putBoolean(KEY_JS,              s.javaScriptEnabled)
            .putBoolean(KEY_JS_WINDOWS,      s.javaScriptCanOpenWindows)
            .putBoolean(KEY_ADBLOCK,         s.adBlockEnabled)
            .putBoolean(KEY_TRACKERS,        s.blockTrackersEnabled)
            .putBoolean(KEY_COSMETIC,        s.cosmeticFilteringEnabled)
            .putBoolean(KEY_CROSS_DOMAIN_POPUPS, s.blockCrossDomainPopups)
            .putBoolean(KEY_COOKIES,         s.cookiesEnabled)
            .putBoolean(KEY_3P_COOKIES,      s.thirdPartyCookiesEnabled)
            .putBoolean(KEY_BLOCK_MIXED,     s.blockMixedContent)
            .putBoolean(KEY_SAFE_BROWSING,   s.safeBrowsingEnabled)
            .putString( KEY_UA_MODE,         s.userAgentMode.name)
            .putString( KEY_CUSTOM_UA,       s.customUserAgent)
            .putBoolean(KEY_DOM_STORAGE,     s.domStorageEnabled)
            .putBoolean(KEY_MEDIA_GESTURE,   s.mediaPlaybackRequiresGesture)
            .putBoolean(KEY_IMAGES,          s.loadImagesAutomatically)
            .putInt(    KEY_TEXT_ZOOM,       s.textZoom)
            .putBoolean(KEY_WIDE_VIEWPORT,   s.useWideViewPort)
            .putBoolean(KEY_OVERVIEW,        s.loadWithOverviewMode)
            .putBoolean(KEY_ZOOM,            s.builtInZoomEnabled)
            .putString( KEY_CACHE,           s.cacheMode.name)
            .putBoolean(KEY_RESTORE_TABS,    s.restoreTabsOnStartup)
            .apply()
    }

    companion object {
        private const val PREFS_NAME  = "browser_settings"
        private const val KEY_JS             = "js_enabled"
        private const val KEY_JS_WINDOWS     = "js_open_windows"
        private const val KEY_ADBLOCK        = "adblock_enabled"
        private const val KEY_TRACKERS       = "trackers_enabled"
        private const val KEY_COSMETIC       = "cosmetic_enabled"
        private const val KEY_CROSS_DOMAIN_POPUPS = "cross_domain_popups"
        private const val KEY_COOKIES        = "cookies"
        private const val KEY_3P_COOKIES     = "third_party_cookies"
        private const val KEY_BLOCK_MIXED    = "block_mixed"
        private const val KEY_SAFE_BROWSING  = "safe_browsing"
        private const val KEY_UA_MODE        = "ua_mode"
        private const val KEY_CUSTOM_UA      = "custom_ua"
        private const val KEY_DOM_STORAGE    = "dom_storage"
        private const val KEY_MEDIA_GESTURE  = "media_gesture"
        private const val KEY_IMAGES         = "images"
        private const val KEY_TEXT_ZOOM      = "text_zoom"
        private const val KEY_WIDE_VIEWPORT  = "wide_viewport"
        private const val KEY_OVERVIEW       = "overview"
        private const val KEY_ZOOM           = "zoom"
        private const val KEY_CACHE          = "cache_mode"
        private const val KEY_RESTORE_TABS   = "restore_tabs"

        @Volatile private var instance: BrowserSettingsStore? = null

        fun get(context: Context): BrowserSettingsStore =
            instance ?: synchronized(this) {
                instance ?: BrowserSettingsStore(
                    context.applicationContext.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
                ).also { instance = it }
            }
    }
}

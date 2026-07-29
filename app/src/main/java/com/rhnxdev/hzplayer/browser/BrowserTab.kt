package com.rhnxdev.hzplayer.browser

import android.graphics.Bitmap
import android.os.Bundle

import com.rhnxdev.hzplayer.browser.media.DetectedMediaItem

data class BrowserTab(
    val id: String,
    val title: String = "",
    val url: String = "",
    val icon: Bitmap? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Tab that opened this one via popup — back returns here when history is empty. */
    val parentTabId: String? = null,
    /** Saved WebView state for freeze/thaw. */
    val savedState: Bundle? = null,
    /** Sniffed media items for this tab. */
    val detectedMedia: List<DetectedMediaItem> = emptyList(),
)


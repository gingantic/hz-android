package com.rhnxdev.hzplayer.browser

import android.graphics.Bitmap
import android.os.Bundle

data class BrowserTab(
    val id: String,
    val title: String = "",
    val url: String = "",
    val icon: Bitmap? = null,
    val progress: Int = 0,
    val isLoading: Boolean = false,
    val canGoBack: Boolean = false,
    val canGoForward: Boolean = false,
    /** Saved WebView state for freeze/thaw. */
    val savedState: Bundle? = null,
)

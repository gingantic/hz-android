package com.rhnxdev.hzplayer.browser

import android.webkit.WebView

/**
 * Data class representing a pending cross-domain pop-up request.
 * Holds the unattached temporary WebView until user approves or denies.
 */
data class PendingPopupRequest(
    val tempWebView: WebView?,
    val parentUrl: String,
    val targetUrl: String,
    val targetDomain: String,
    /** Tab that requested the pop-up — becomes the new tab's parent when allowed. */
    val sourceTabId: String? = null,
)

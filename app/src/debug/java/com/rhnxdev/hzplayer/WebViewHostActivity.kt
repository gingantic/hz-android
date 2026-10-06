package com.rhnxdev.hzplayer

import android.app.Activity
import android.os.Bundle
import android.webkit.WebView

/**
 * Debug-only window host for instrumented WebView tests.
 *
 * A WebView only runs `View.post` callbacks once it is attached to a window
 * (AOSP `View.post` defers to `dispatchAttachedToWindow`), and the sniffer
 * injection is delivered through such a post — device tests need a real window.
 */
class WebViewHostActivity : Activity() {

    lateinit var webView: WebView
        private set

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        webView = WebView(this)
        setContentView(webView)
    }

    override fun onDestroy() {
        webView.destroy()
        super.onDestroy()
    }
}

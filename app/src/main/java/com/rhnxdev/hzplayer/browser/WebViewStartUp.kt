package com.rhnxdev.hzplayer.browser

import android.content.Context
import android.util.Log
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewOutcomeReceiver
import androidx.webkit.WebViewStartUpConfig
import androidx.webkit.WebViewStartUpResult
import androidx.webkit.WebViewStartupException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asExecutor

/**
 * Starts WebView explicitly when the browser opens, so its heavy first-use
 * startup runs mostly on a background thread instead of inside the first
 * `WebView(...)` call on the main thread.
 *
 * Deliberately not called at app launch: users who never open the browser
 * should not pay for loading WebView. Safe to call repeatedly. See
 * https://developer.android.com/develop/ui/views/layout/webapps/optimize-webview-startup
 */
object WebViewStartUp {

    private const val TAG = "WebViewStartUp"

    fun start(context: Context) {
        // No WebView provider installed/enabled throws; the browser then fails
        // the same way it always did, so there is nothing more to do here.
        runCatching {
            val config = WebViewStartUpConfig.Builder(Dispatchers.IO.asExecutor()).build()
            WebViewCompat.startUpWebView(
                context.applicationContext,
                config,
                object : WebViewOutcomeReceiver<WebViewStartUpResult, WebViewStartupException> {
                    override fun onResult(result: WebViewStartUpResult) {
                        Log.d(TAG, "startup done")
                    }

                    override fun onError(error: WebViewStartupException) {
                        Log.w(TAG, "startup failed", error)
                    }
                },
            )
        }.onFailure { Log.w(TAG, "startUpWebView unavailable", it) }
    }
}

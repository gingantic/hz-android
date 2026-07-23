package com.rhnxdev.hzplayer.browser

import android.annotation.SuppressLint
import android.graphics.Bitmap
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.UUID

import com.rhnxdev.hzplayer.browser.adblock.AdBlockEngine
import com.rhnxdev.hzplayer.browser.media.DetectedMediaItem
import com.rhnxdev.hzplayer.browser.media.MediaSnifferBridge
import com.rhnxdev.hzplayer.browser.media.MediaSnifferEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream

/**
 * Manages tab metadata and WebView instance pool.
 * WebViews are NOT created here — they're created in [AndroidView] with
 * Activity context and registered via [registerWebView].
 */
class TabManager(
    initialSettings: BrowserSettings = BrowserSettings(),
) {

    /** Current browser settings — update via [applySettings]. */
    var settings: BrowserSettings = initialSettings
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val liveViews = mutableMapOf<String, WebView>()
    private val _tabs = mutableStateOf(listOf<BrowserTab>())
    var tabs by _tabs
        private set

    var activeTabId by mutableStateOf<String?>(null)

    companion object {
        private const val MAX_LIVE = 6

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.0.0 Safari/537.36"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.6422.72 Mobile Safari/537.36"
    }

    /** The URL currently shown in the URL bar. */
    var urlInput by mutableStateOf("")

    /** Fullscreen custom view (HTML5 video full screen). */
    var customView by mutableStateOf<android.view.View?>(null)
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
    }

    // ── Tab CRUD ────────────────────────────────────────────────

    fun restoreSession(restoredTabs: List<BrowserTab>, targetActiveTabId: String?) {
        if (restoredTabs.isEmpty()) return
        _tabs.value = restoredTabs
        val targetId = targetActiveTabId?.takeIf { id -> restoredTabs.any { it.id == id } }
            ?: restoredTabs.first().id
        switchTab(targetId)
    }

    fun createTab(url: String = ""): String {
        val id = UUID.randomUUID().toString().take(8)
        val tab = BrowserTab(id = id, url = url)
        _tabs.value = _tabs.value + tab
        switchTab(id)
        return id
    }

    fun closeTab(id: String) {
        if (_tabs.value.size <= 1) {
            _tabs.value = emptyList()
            activeTabId = null
            urlInput = ""
            liveViews.remove(id)?.destroy()
            return
        }
        val idx = _tabs.value.indexOfFirst { it.id == id }
        _tabs.value = _tabs.value.filter { it.id != id }
        liveViews.remove(id)?.destroy()

        val newIdx = if (idx < _tabs.value.size) idx else _tabs.value.size - 1
        if (newIdx >= 0) {
            switchTab(_tabs.value[newIdx].id)
        } else {
            activeTabId = null
            urlInput = ""
        }
    }

    fun switchTab(id: String) {
        activeTabId = id
        val tab = _tabs.value.find { it.id == id } ?: return
        urlInput = tab.url
        onTabSwitched?.invoke(id)
    }

    var onTabSwitched: ((tabId: String) -> Unit)? = null
    var onPageVisited: ((url: String, title: String) -> Unit)? = null
    var onCrossDomainPopupBlocked: ((blockedUrl: String, blockedDomain: String) -> Unit)? = null
    var onCrossDomainPopupRequested: ((PendingPopupRequest) -> Unit)? = null
    var denyAllCrossDomainPopupsThisSession: Boolean = false

    fun navigate(tabId: String, url: String) {
        val safeUrl = sanitizeUrl(url)
        updateTab(tabId) { it.copy(url = safeUrl, isLoading = true, detectedMedia = emptyList()) }
        urlInput = safeUrl
        liveViews[tabId]?.loadUrl(safeUrl)
    }

    fun goBack() {
        val id = activeTabId ?: return
        liveViews[id]?.goBack()
    }

    fun goForward() {
        val id = activeTabId ?: return
        liveViews[id]?.goForward()
    }

    fun reload() {
        val id = activeTabId ?: return
        liveViews[id]?.reload()
    }

    fun stopLoading() {
        val id = activeTabId ?: return
        liveViews[id]?.stopLoading()
    }

    fun clearMediaForTab(tabId: String) {
        updateTab(tabId) { it.copy(detectedMedia = emptyList()) }
    }

    fun updateSelectedMediaQuality(tabId: String, itemId: String, qualityUrl: String) {
        updateTab(tabId) { tab ->
            val updatedMedia = tab.detectedMedia.map { item ->
                if (item.id == itemId) {
                    item.copy(selectedQualityUrl = qualityUrl)
                } else item
            }
            tab.copy(detectedMedia = updatedMedia)
        }
    }

    fun processCapturedMedia(
        tabId: String,
        mediaUrl: String,
        pageTitle: String,
        requestHeaders: Map<String, String> = emptyMap(),
        mimeType: String = ""
    ) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        if (mediaUrl.isBlank()) return

        val finalHeaders = requestHeaders.toMutableMap()
        if (!finalHeaders.containsKey("User-Agent")) {
            val ua = liveViews[tabId]?.settings?.userAgentString
            if (!ua.isNullOrBlank()) {
                finalHeaders["User-Agent"] = ua
            }
        }

        val pageUrl = tab.url
        val item = MediaSnifferEngine.createMediaItem(
            rawUrl = mediaUrl,
            pageUrl = pageUrl,
            pageTitle = pageTitle.ifBlank { tab.title },
            requestHeaders = finalHeaders,
            mimeType = mimeType
        )

        val currentMedia = tab.detectedMedia
        if (currentMedia.any { it.url == item.url }) return

        val updatedList = currentMedia + item
        updateTab(tabId) { it.copy(detectedMedia = updatedList) }

        if (item.mediaType == com.rhnxdev.hzplayer.browser.media.MediaType.STREAM_HLS || item.url.contains(".m3u8")) {
            scope.launch {
                val parsedItem = MediaSnifferEngine.parseHlsQualities(item)
                if (parsedItem.subQualities.isNotEmpty()) {
                    updateTab(tabId) { t ->
                        val newList = t.detectedMedia.map { m ->
                            if (m.id == item.id) parsedItem else m
                        }
                        t.copy(detectedMedia = newList)
                    }
                }
            }
        }
    }

    // ── WebView management ────────────────────────────────────────

    /**
     * Register a WebView created by [AndroidView] (Activity context).
     * If tab has a saved state, restores it.
     * If tab has a pending URL, loads it.
     */
    @SuppressLint("SetJavaScriptEnabled")
    fun registerWebView(tabId: String, wv: WebView) {
        // Skip if same instance already registered (tab re-composition)
        if (liveViews[tabId] === wv) return
        liveViews[tabId]?.destroy()
        liveViews[tabId] = wv

        applySettingsToView(wv, settings)

        // Inject Media Sniffer JS bridge interface
        wv.addJavascriptInterface(
            MediaSnifferBridge { mediaUrl, pageTitle, mimeType, jsHeaders ->
                val id = resolveTabId(wv) ?: return@MediaSnifferBridge
                val enrichedHeaders = jsHeaders.toMutableMap()
                val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaUrl)
                if (!cookie.isNullOrBlank() && !enrichedHeaders.containsKey("Cookie")) {
                    enrichedHeaders["Cookie"] = cookie
                }
                scope.launch(Dispatchers.Main) {
                    processCapturedMedia(id, mediaUrl, pageTitle, requestHeaders = enrichedHeaders, mimeType = mimeType)
                }
            },
            MediaSnifferBridge.INTERFACE_NAME
        )

        wv.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val urlStr = request.url?.toString() ?: ""
                if (urlStr.isNotBlank()) {
                    val reqHeaders = request.requestHeaders ?: emptyMap()
                    val tabId = resolveTabId(view)
                    val pageUrl = reqHeaders["Referer"]
                        ?: (if (tabId != null) _tabs.value.find { it.id == tabId }?.url else null)
                        ?: ""

                    if (AdBlockEngine.shouldBlockRequest(requestUrl = urlStr, pageUrl = pageUrl, settings = settings)) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    if (MediaSnifferEngine.isMediaUrl(urlStr, reqHeaders)) {
                        if (tabId != null) {
                            val enriched = reqHeaders.toMutableMap()
                            val cookie = android.webkit.CookieManager.getInstance().getCookie(urlStr)
                            if (!cookie.isNullOrBlank() && !enriched.containsKey("Cookie")) {
                                enriched["Cookie"] = cookie
                            }
                            scope.launch(Dispatchers.Main) {
                                processCapturedMedia(tabId, urlStr, "", enriched)
                            }
                        }
                    }
                }
                return super.shouldInterceptRequest(view, request)
            }


            override fun onPageStarted(view: WebView, url: String, favicon: Bitmap?) {
                val id = resolveTabId(view) ?: return
                updateTab(id) {
                    it.copy(
                        url = url, title = view.title ?: "", icon = favicon,
                        isLoading = true, canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward(),
                        detectedMedia = emptyList(),
                    )
                }
                urlInput = url
                MediaSnifferBridge.injectSnifferJs(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val id = resolveTabId(view) ?: return
                updateTab(id) {
                    it.copy(
                        title = view.title ?: "", isLoading = false,
                        canGoBack = view.canGoBack(), canGoForward = view.canGoForward(),
                    )
                }
                MediaSnifferBridge.injectSnifferJs(view)

                if (settings.adBlockEnabled && settings.cosmeticFilteringEnabled && url.isNotBlank()) {
                    val cosmeticCss = AdBlockEngine.getCosmeticCss(url, settings)
                    if (cosmeticCss.isNotBlank()) {
                        val escapedCss = cosmeticCss.replace("'", "\\'").replace("\n", " ")
                        val js = """
                            (function() {
                                try {
                                    var old = document.getElementById('hz-adblock-css');
                                    if (old) old.remove();
                                    var style = document.createElement('style');
                                    style.id = 'hz-adblock-css';
                                    style.innerHTML = '$escapedCss';
                                    (document.head || document.documentElement).appendChild(style);
                                } catch(e) {}
                            })();
                        """.trimIndent()
                        view.evaluateJavascript(js, null)
                    }
                }

                if (url.isNotBlank() && url != "about:blank") {
                    val pageTitle = view.title?.ifBlank { url } ?: url
                    onPageVisited?.invoke(url, pageTitle)
                }
            }


            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                if (request.isForMainFrame) {
                    urlInput = request.url.toString()
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                urlInput = url
                return false
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                handler?.proceed()
            }
        }

        wv.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: android.view.View?, callback: WebChromeClient.CustomViewCallback?) {
                if (customView != null) {
                    callback?.onCustomViewHidden()
                    return
                }
                customView = view
                customViewCallback = callback
            }

            override fun onHideCustomView() {
                hideCustomView()
            }

            override fun onReceivedTitle(view: WebView, title: String) {
                val id = resolveTabId(view) ?: return
                updateTab(id) { it.copy(title = title) }
                val currentUrl = view.url ?: ""
                if (currentUrl.isNotBlank() && currentUrl != "about:blank" && title.isNotBlank()) {
                    onPageVisited?.invoke(currentUrl, title)
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                val id = resolveTabId(view) ?: return
                updateTab(id) { it.copy(progress = newProgress) }
                if (newProgress == 30 || newProgress == 60) {
                    MediaSnifferBridge.injectSnifferJs(view)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                val id = resolveTabId(view) ?: return
                updateTab(id) { it.copy(icon = icon) }
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (!settings.javaScriptEnabled || !settings.javaScriptCanOpenWindows) return false
                if (resultMsg == null) return false
                val parentUrl = view.url ?: ""

                val tempWebView = WebView(view.context)
                applySettingsToView(tempWebView, settings)

                var isEvaluated = false

                tempWebView.webViewClient = object : WebViewClient() {
                    private fun handleCrossDomainPopup(v: WebView, popupUrl: String) {
                        val domain = getRootDomain(popupUrl)
                        if (denyAllCrossDomainPopupsThisSession) {
                            v.post {
                                try {
                                    v.stopLoading()
                                    v.destroy()
                                } catch (_: Exception) {}
                                onCrossDomainPopupBlocked?.invoke(popupUrl, domain)
                            }
                        } else {
                            onCrossDomainPopupRequested?.invoke(
                                PendingPopupRequest(
                                    tempWebView = v,
                                    parentUrl = parentUrl,
                                    targetUrl = popupUrl,
                                    targetDomain = domain,
                                )
                            )
                        }
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val popupUrl = request.url?.toString() ?: ""
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, popupUrl)) {
                            isEvaluated = true
                            v.stopLoading()
                            handleCrossDomainPopup(v, popupUrl)
                            return true
                        }
                        if (!isEvaluated) {
                            isEvaluated = true
                            val newTabId = createTab(popupUrl)
                            registerWebView(newTabId, v)
                        }
                        return false
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, url)) {
                            isEvaluated = true
                            v.stopLoading()
                            handleCrossDomainPopup(v, url)
                            return
                        }
                        if (!isEvaluated) {
                            isEvaluated = true
                            val newTabId = createTab(url)
                            registerWebView(newTabId, v)
                        }
                    }

                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val popupUrl = request.url?.toString() ?: ""
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, popupUrl)) {
                            isEvaluated = true
                            handleCrossDomainPopup(v, popupUrl)
                            return createDummyResponse(popupUrl)
                        }
                        return super.shouldInterceptRequest(v, request)
                    }
                }

                val transport = resultMsg.obj as? WebView.WebViewTransport
                if (transport != null) {
                    transport.webView = tempWebView
                    resultMsg.sendToTarget()
                    return true
                }
                return false
            }

            override fun onCloseWindow(window: WebView) {
                val id = resolveTabId(window) ?: return
                closeTab(id)
            }
        }

        // Load pending URL or restore state
        val tab = _tabs.value.find { it.id == tabId }
        if (tab != null) {
            if (tab.savedState != null) {
                wv.restoreState(tab.savedState)
            } else if (tab.url.isNotBlank()) {
                wv.loadUrl(tab.url)
            }
        }
    }

    /** Apply new settings to every live WebView and remember for future registrations. */
    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(newSettings: BrowserSettings) {
        settings = newSettings
        liveViews.values.forEach { applySettingsToView(it, newSettings) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettingsToView(wv: WebView, s: BrowserSettings) {
        wv.settings.javaScriptEnabled                  = s.javaScriptEnabled
        wv.settings.javaScriptCanOpenWindowsAutomatically = s.javaScriptCanOpenWindows
        wv.settings.setSupportMultipleWindows(s.javaScriptEnabled && s.javaScriptCanOpenWindows)
        wv.settings.domStorageEnabled                  = s.domStorageEnabled
        wv.settings.databaseEnabled                    = true
        wv.settings.mediaPlaybackRequiresUserGesture   = s.mediaPlaybackRequiresGesture
        wv.settings.loadsImagesAutomatically           = s.loadImagesAutomatically
        wv.settings.textZoom                           = s.textZoom
        wv.settings.loadWithOverviewMode               = s.loadWithOverviewMode
        wv.settings.useWideViewPort                    = s.useWideViewPort
        wv.settings.builtInZoomControls                = s.builtInZoomEnabled
        wv.settings.displayZoomControls                = false
        wv.settings.setSupportZoom(true)
        wv.settings.allowFileAccess                    = true
        wv.settings.allowContentAccess                 = true
        @Suppress("DEPRECATION")
        wv.settings.allowFileAccessFromFileURLs        = true
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs   = true
        wv.settings.mixedContentMode = if (s.blockMixedContent)
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.safeBrowsingEnabled                = s.safeBrowsingEnabled
        wv.settings.cacheMode = when (s.cacheMode) {
            BrowserCacheMode.NO_CACHE    -> android.webkit.WebSettings.LOAD_NO_CACHE
            BrowserCacheMode.CACHE_ONLY  -> android.webkit.WebSettings.LOAD_CACHE_ONLY
            BrowserCacheMode.NORMAL      -> android.webkit.WebSettings.LOAD_DEFAULT
        }
        // User Agent
        val ua = when (s.userAgentMode) {
            UserAgentMode.MOBILE  -> MOBILE_UA
            UserAgentMode.DESKTOP -> DESKTOP_UA
            UserAgentMode.CUSTOM  -> s.customUserAgent.ifBlank { null }
        }
        if (ua != null) wv.settings.userAgentString = ua
        else wv.settings.userAgentString = null   // reset to WebView default

        // Cookies
        val mgr = android.webkit.CookieManager.getInstance()
        mgr.setAcceptCookie(s.cookiesEnabled)
        mgr.setAcceptThirdPartyCookies(wv, s.thirdPartyCookiesEnabled)
    }

    /** Apply CookieManager settings (call after saving settings). */
    fun applyCookieSettings(s: BrowserSettings) {
        val mgr = android.webkit.CookieManager.getInstance()
        mgr.setAcceptCookie(s.cookiesEnabled)
        liveViews.values.forEach { wv ->
            mgr.setAcceptThirdPartyCookies(wv, s.thirdPartyCookiesEnabled)
        }
    }

    /** Freeze oldest non-active WebViews to stay within pool limit. */
    fun trimPool(keepId: String) {
        val excess = liveViews.size - MAX_LIVE
        if (excess <= 0) return
        liveViews.keys
            .filter { it != keepId }
            .sorted()
            .take(excess)
            .forEach { freezeTab(it) }
    }

    private fun freezeTab(id: String) {
        val wv = liveViews[id] ?: return
        val bundle = android.os.Bundle()
        wv.saveState(bundle)
        updateTab(id) { it.copy(savedState = bundle) }
        wv.stopLoading()
        wv.onPause()
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        liveViews.remove(id)
    }

    /** Get the WebView for a specific tab (null if frozen or not yet created). */
    fun getWebView(tabId: String): WebView? = liveViews[tabId]

    // ── Lifecycle ────────────────────────────────────────────────

    fun pause() {
        activeTabId?.let { liveViews[it]?.onPause() }
    }

    fun resume() {
        activeTabId?.let { liveViews[it]?.onResume() }
    }

    fun destroy() {
        liveViews.values.forEach { it.destroy() }
        liveViews.clear()
        _tabs.value = emptyList()
        activeTabId = null
        urlInput = ""
    }

    // ── Internals ────────────────────────────────────────────────

    private fun resolveTabId(view: WebView): String? {
        return liveViews.entries.firstOrNull { it.value == view }?.key
    }

    private fun updateTab(id: String, transform: (BrowserTab) -> BrowserTab) {
        _tabs.value = _tabs.value.map { if (it.id == id) transform(it) else it }
    }

    private fun sanitizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return "about:blank"
        val knownSchemes = listOf("about:", "file:", "data:", "javascript:", "blob:", "mailto:", "tel:")
        if (knownSchemes.any { trimmed.startsWith(it, ignoreCase = true) }) return trimmed
        if (trimmed.startsWith("http://", ignoreCase = true) || trimmed.startsWith("https://", ignoreCase = true)) return trimmed

        // If input contains spaces or has no dot, treat as search query
        if (trimmed.contains(" ") || !trimmed.contains(".")) {
            val encodedQuery = android.net.Uri.encode(trimmed)
            return "https://www.google.com/search?q=$encodedQuery"
        }

        return "https://$trimmed"
    }

    private fun getRootDomain(urlStr: String): String {
        val host = try { android.net.Uri.parse(urlStr).host?.lowercase() ?: "" } catch (_: Exception) { "" }
        val parts = host.split(".")
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else host
    }

    private fun isCrossDomain(url1: String, url2: String): Boolean {
        if (url1.isBlank() || url2.isBlank()) return false
        val d1 = getRootDomain(url1)
        val d2 = getRootDomain(url2)
        if (d1.isBlank() || d2.isBlank()) return false
        return d1 != d2
    }

    private fun createDummyResponse(urlStr: String = ""): WebResourceResponse {
        val lowerUrl = urlStr.lowercase()
        val mimeType = when {
            lowerUrl.contains(".js") || lowerUrl.contains("javascript") || lowerUrl.contains("/js/") -> "application/javascript"
            lowerUrl.contains(".css") -> "text/css"
            lowerUrl.contains(".png") || lowerUrl.contains(".jpg") || lowerUrl.contains(".jpeg") ||
            lowerUrl.contains(".gif") || lowerUrl.contains(".webp") || lowerUrl.contains(".svg") || lowerUrl.contains(".ico") -> "image/png"
            lowerUrl.contains(".mp4") || lowerUrl.contains(".webm") || lowerUrl.contains(".m3u8") || lowerUrl.contains(".mpd") -> "video/mp4"
            else -> "text/html"
        }

        val content = when (mimeType) {
            "text/html" -> "<!DOCTYPE html><html><head><title></title></head><body></body></html>"
            "application/javascript" -> "/* blocked */"
            "text/css" -> "/* blocked */"
            else -> ""
        }

        val responseHeaders = mapOf(
            "Access-Control-Allow-Origin" to "*",
            "Access-Control-Allow-Methods" to "GET, POST, OPTIONS",
            "Access-Control-Allow-Headers" to "*"
        )

        return WebResourceResponse(
            mimeType,
            "UTF-8",
            200,
            "OK",
            responseHeaders,
            java.io.ByteArrayInputStream(content.toByteArray(Charsets.UTF_8))
        )
    }
}

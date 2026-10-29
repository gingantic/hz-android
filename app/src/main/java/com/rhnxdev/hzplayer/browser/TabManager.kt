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
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewFeature
import java.util.UUID

import com.rhnxdev.hzplayer.browser.adblock.AdBlockEngine
import com.rhnxdev.hzplayer.browser.adblock.ElementPickerBridge
import com.rhnxdev.hzplayer.browser.adblock.PickedElement
import com.rhnxdev.hzplayer.browser.media.MediaSnifferBridge
import com.rhnxdev.hzplayer.browser.media.MediaSnifferEngine
import com.rhnxdev.hzplayer.core.util.DebouncedAction
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import org.json.JSONObject

/**
 * Manages tab metadata and WebView instance pool.
 * WebViews are NOT created here — they're created in [AndroidView] with
 * Activity context and registered via [registerWebView].
 */
class TabManager(
    initialSettings: BrowserSettings = BrowserSettings(),
    initialNetworkState: BrowserNetworkState = BrowserNetworkState(),
) {

    /** Current browser settings — update via [applySettings]. */
    var settings: BrowserSettings = initialSettings
        private set

    /** Latest connection state — update via [onNetworkStateChanged]. */
    var networkState: BrowserNetworkState = initialNetworkState
        private set

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val liveViews = mutableMapOf<String, WebView>()

    /**
     * Main-frame HTTP errors (4xx/5xx) waiting for the page to finish. Many
     * sites send a useful error body of their own, so the error page is only
     * shown when the finished page turns out to be blank (see [showIfBlank]).
     */
    private val pendingHttpErrors = HashMap<String, BrowserError>()

    /**
     * Main-frame certificate failures whose tab view still shows the previous page
     * (a link or redirect into the failing host). Reported by onPageFinished, which
     * receives the failing URL, once that URL is known to be the one that failed.
     */
    private val pendingSslErrors = HashMap<String, BrowserError>()

    /**
     * Tabs whose error page must clear on the next commit even when the URL is
     * unchanged (retry, or a URL typed again). A commit for a different URL
     * clears the error anyway; a commit of the failed URL itself — an error
     * document restored from history — keeps the error page up.
     */
    private val clearErrorOnCommit = HashSet<String>()

    /** Per-tab safety timers that release deferred images — see [deferImages]. */
    private val imageDeferTimeouts = HashMap<String, Job>()

    /** Last progress value pushed to tab state, per tab — see [onProgressChanged]. */
    private val lastReportedProgress = HashMap<String, Int>()

    /** Coalesces per-keystroke custom-UA edits into one reload of the live tabs. */
    private val renderReloads = DebouncedAction(scope, CUSTOM_UA_RELOAD_DEBOUNCE_MS)

    /** A tab's sniffer secrets — see [snifferTokens]. */
    private data class SnifferToken(val current: String, val previous: String? = null)

    /**
     * Per-tab sniffer secrets. The outgoing token stays valid until the new
     * document commits, so a stopped or failed navigation doesn't orphan the
     * still-visible page. ConcurrentHashMap: the bridge validator reads it on
     * the JavaBridge thread.
     */
    private val snifferTokens = ConcurrentHashMap<String, SnifferToken>()
    private val _tabs = mutableStateOf(listOf<BrowserTab>())
    var tabs by _tabs
        private set

    var activeTabId by mutableStateOf<String?>(null)

    companion object {
        private const val MAX_LIVE = 6

        /**
         * How many times in a row a tab is auto-reloaded after its renderer dies
         * before we give up. Stops a page that reliably kills the renderer from
         * looping reload → crash → reload forever.
         */
        private const val MAX_RENDERER_RECOVERIES = 2

        /** Layout width (CSS px) forced on pages in desktop mode — mirrors Chrome's "Desktop site". */
        private const val DESKTOP_VIEWPORT_WIDTH = 1024

        /** Quiet period after the last custom-UA keystroke before live tabs are reloaded. */
        private const val CUSTOM_UA_RELOAD_DEBOUNCE_MS = 500L

        /**
         * Longest a slow-link page may hold back its images. Normally they are
         * released when the page finishes; this bounds pages that never finish.
         */
        private const val IMAGE_DEFER_MAX_MS = 10_000L

        /** Smallest progress step pushed to tab state; each push recomposes the browser screen. */
        private const val PROGRESS_STEP = 5

        /**
         * Reports whether a finished page has nothing visible: no text and no
         * media elements. Evaluates to the string "true" / "false".
         */
        private const val BLANK_PAGE_JS =
            "(function(){var b=document.body;if(!b)return true;" +
                "return b.innerText.trim().length===0&&" +
                "b.querySelector('img,video,canvas,svg,iframe,object,embed')===null;})()"

        private const val DESKTOP_UA =
            "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.0.0 Safari/537.36"

        private const val MOBILE_UA =
            "Mozilla/5.0 (Linux; Android 14; Pixel 8) AppleWebKit/537.36 (KHTML, like Gecko) " +
            "Chrome/125.0.6422.72 Mobile Safari/537.36"

        /**
         * JS that (re)installs the cosmetic-filter style element for [css].
         * The CSS is JSON-quoted — hand-escaping broke on backslashes — and
         * applied via textContent so it never reaches the HTML parser.
         */
        internal fun cosmeticCssJs(css: String): String {
            val cssLiteral = JSONObject.quote(css)
            return """
                (function() {
                    try {
                        var old = document.getElementById('hz-adblock-css');
                        if (old) old.remove();
                        var style = document.createElement('style');
                        style.id = 'hz-adblock-css';
                        style.textContent = $cssLiteral;
                        (document.head || document.documentElement).appendChild(style);
                    } catch(e) {}
                })();
            """.trimIndent()
        }
    }

    /** The URL currently shown in the URL bar. */
    var urlInput by mutableStateOf("")

    /**
     * True while the user is editing the URL bar. Navigation callbacks must not
     * write [urlInput] then: overwriting the user's text also makes the bar's
     * trailing action flip between Go and Clear as the page navigates.
     */
    var isUrlBarFocused by mutableStateOf(false)

    /** Fullscreen custom view (HTML5 video full screen). */
    var customView by mutableStateOf<android.view.View?>(null)
        private set
    private var customViewCallback: WebChromeClient.CustomViewCallback? = null

    fun hideCustomView() {
        customViewCallback?.onCustomViewHidden()
        customViewCallback = null
        customView = null
    }

    // ── JavaScript dialogs (alert / confirm / prompt / beforeunload) ──────

    /**
     * The JS dialog currently awaiting the user. WebView blocks the page's JS
     * thread until the matching [android.webkit.JsResult] is confirmed/cancelled,
     * so exactly one can be live at a time — a second request while one is open
     * is auto-cancelled rather than queued.
     */
    var jsDialog by mutableStateOf<JsDialogRequest?>(null)
        private set

    /** Resolve the open JS dialog: positive = OK/leave, else cancel. [input] applies to prompt only. */
    fun resolveJsDialog(confirmed: Boolean, input: String? = null) {
        val d = jsDialog ?: return
        jsDialog = null
        when (val r = d.result) {
            is JsDialogResult.Confirm -> if (confirmed) r.result.confirm() else r.result.cancel()
            is JsDialogResult.Prompt  -> if (confirmed) r.result.confirm(input ?: d.defaultValue) else r.result.cancel()
        }
    }

    private fun showJsDialog(request: JsDialogRequest): Boolean {
        // Only one modal JS dialog at a time; reject extras so WebView unblocks.
        if (jsDialog != null) return false
        jsDialog = request
        return true
    }

    // ── File upload (<input type="file">) ─────────────────────────────────

    private var fileChooserCallback: android.webkit.ValueCallback<Array<android.net.Uri>>? = null

    /**
     * Fired when a page opens a file picker. The host Activity owns the
     * ActivityResultLauncher (the WebChromeClient has no Activity context), so it
     * launches the chooser with [FileChooserParams] and later calls
     * [deliverFileChooserResult]. Returns false here if a chooser is already open.
     */
    var onShowFileChooser: ((FileChooserRequest) -> Unit)? = null

    /** Deliver the picked URIs (or null if cancelled) back to the waiting page. */
    fun deliverFileChooserResult(uris: Array<android.net.Uri>?) {
        fileChooserCallback?.onReceiveValue(uris)
        fileChooserCallback = null
    }

    // ── Video playback / PiP state ────────────────────────────

    // ── Element picker ("block element") ──────────────────────────

    /** True while the user is picking a page element to block. */
    var elementPickerActive by mutableStateOf(false)
        private set

    /** The element currently outlined, or null before the first tap. */
    var pickedElement by mutableStateOf<PickedElement?>(null)
        private set

    /** True when the active tab can enter picker mode (JS on, real http(s) page). */
    val canStartElementPicker: Boolean
        get() {
            if (!settings.javaScriptEnabled) return false
            val id = activeTabId ?: return false
            return isPickableUrl(_tabs.value.find { it.id == id }?.url)
        }

    /** Enter picker mode on the active tab. Returns false when it can't start. */
    fun startElementPicker(): Boolean {
        if (!canStartElementPicker) return false
        val view = activeWebView() ?: return false
        ElementPickerBridge.injectPickerJs(view)
        ElementPickerBridge.eval(view, "start()")
        elementPickerActive = true
        pickedElement = null
        return true
    }

    /** Leave picker mode without changing anything. */
    fun cancelElementPicker() {
        if (elementPickerActive) activeWebView()?.let { ElementPickerBridge.eval(it, "stop()") }
        resetElementPickerState()
    }

    /** Select the parent of the current element — for when the pick is too specific. */
    fun widenElementSelection() {
        if (!elementPickerActive) return
        activeWebView()?.let { ElementPickerBridge.eval(it, "widen()") }
    }

    /**
     * Hide the picked element in the live page and leave picker mode. Hiding is
     * immediate feedback — persisting the rule is the caller's job.
     */
    fun applyPickedHideAndExit(): PickedElement? {
        val picked = pickedElement
        val view = activeWebView()
        if (picked != null && view != null) {
            ElementPickerBridge.hide(view, picked.selector)   // stop()s the picker itself
        } else {
            // Nothing was picked — still tear the picker down so the page is usable.
            view?.let { ElementPickerBridge.eval(it, "stop()") }
        }
        resetElementPickerState()
        return picked
    }

    // ── Video playback / PiP state ──────────────────────────────

    /** Tab IDs with at least one actively playing HTML5 video. */
    private val playingTabs = mutableStateOf(setOf<String>())

    /** True when the active tab has a playing video OR a fullscreen video view is showing. */
    val isVideoPlaying: Boolean
        get() = customView != null || activeTabId?.let { it in playingTabs.value } == true

    /** Fired when the page invokes the web PiP API (site PiP button). */
    var onPipRequested: (() -> Unit)? = null

    private fun setTabPlaying(tabId: String, playing: Boolean) {
        playingTabs.value = if (playing) playingTabs.value + tabId else playingTabs.value - tabId
    }

    /**
     * Per-tab WebView generation, bumped when a tab's WebView instance must be
     * rebuilt (renderer death). The UI keys its WebView host on this value so a
     * fresh instance is created and the tab's URL is loaded again.
     */
    private val webViewGenerations = mutableStateOf(emptyMap<String, Int>())

    /** Current WebView generation for [tabId]. */
    fun generationOf(tabId: String): Int = webViewGenerations.value[tabId] ?: 0

    /**
     * Consecutive renderer failures per tab, reset on a successful page load or
     * an explicit navigation. Bounds automatic crash recovery.
     */
    private val rendererFailures = HashMap<String, Int>()

    // ── Tab CRUD ────────────────────────────────────────────────

    fun restoreSession(restoredTabs: List<BrowserTab>, targetActiveTabId: String?) {
        if (restoredTabs.isEmpty()) return
        _tabs.value = restoredTabs
        val targetId = targetActiveTabId?.takeIf { id -> restoredTabs.any { it.id == id } }
            ?: restoredTabs.first().id
        switchTab(targetId)
    }

    fun createTab(url: String = "", parentTabId: String? = null): String {
        val id = UUID.randomUUID().toString().take(8)
        val tab = BrowserTab(id = id, url = url, parentTabId = parentTabId)
        _tabs.value = _tabs.value + tab
        switchTab(id)
        return id
    }

    fun closeTab(id: String) {
        setTabPlaying(id, false)
        if (activeTabId == id) resetElementPickerState()
        if (_tabs.value.size <= 1) {
            _tabs.value = emptyList()
            activeTabId = null
            urlInput = ""
            dropTab(id)
            return
        }
        val idx = _tabs.value.indexOfFirst { it.id == id }
        val closedTab = _tabs.value.getOrNull(idx)
        val wasActive = activeTabId == id
        _tabs.value = _tabs.value.filter { it.id != id }
        dropTab(id)

        // If the active popup tab is closed, return to the tab that opened it
        val parentId = closedTab?.parentTabId?.takeIf { pid ->
            wasActive && _tabs.value.any { it.id == pid }
        }
        if (parentId != null) {
            switchTab(parentId)
            return
        }

        val newIdx = if (idx < _tabs.value.size) idx else _tabs.value.size - 1
        if (newIdx >= 0) {
            switchTab(_tabs.value[newIdx].id)
        } else {
            activeTabId = null
            urlInput = ""
        }
    }

    fun switchTab(id: String) {
        // Cancel before the switch so stop() still reaches the tab that owns it.
        cancelElementPicker()
        activeTabId = id
        val tab = _tabs.value.find { it.id == id } ?: return
        urlInput = tab.url
        // This tab failed on the network while it was in the background; if the
        // network is back by the time it comes to the front, retry now.
        if (networkState.online && autoRetryable(tab)) retryTab(id)
        onTabSwitched?.invoke(id)
    }

    var onTabSwitched: ((tabId: String) -> Unit)? = null
    /** Fired when any page finishes loading (including about:blank and error pages). */
    var onLoadFinished: (() -> Unit)? = null
    var onPageVisited: ((url: String, title: String) -> Unit)? = null
    /** Fired when a cross-domain pop-up needs user approval (raises the Allow/Deny sheet). */
    var onCrossDomainPopupRequested: ((PendingPopupRequest) -> Unit)? = null
    /**
     * Fired for a failure that does not replace the page: a link no app can open,
     * a blocked intent:// link, or a certificate error on a sub-resource. The UI
     * shows it briefly; page-level failures go to [BrowserTab.error] instead.
     */
    var onNotice: ((BrowserError) -> Unit)? = null

    /** True when settings request full desktop rendering (viewport + JS spoofing, not just UA). */
    private val isDesktopMode: Boolean
        get() = settings.userAgentMode == UserAgentMode.DESKTOP

    /**
     * Real desktop mode: a desktop UA alone doesn't stop pages with
     * `<meta name="viewport" content="width=device-width">` from rendering their
     * mobile layout. Override the viewport meta to force desktop layout width,
     * keep re-applying it if the site rewrites it, and spoof the touch/platform
     * hints sites use for JS device detection.
     */
    private fun injectDesktopModeJs(view: WebView) {
        val js = """
            (function() {
                if (window.__hzDesktopModeInjected) return;
                window.__hzDesktopModeInjected = true;

                // Spoof desktop JS environment for device-detection scripts
                try {
                    Object.defineProperty(Navigator.prototype, 'platform', { get: function() { return 'Win32'; } });
                    Object.defineProperty(Navigator.prototype, 'maxTouchPoints', { get: function() { return 0; } });
                    Object.defineProperty(window.screen, 'width',  { get: function() { return $DESKTOP_VIEWPORT_WIDTH; } });
                    Object.defineProperty(window.screen, 'availWidth', { get: function() { return $DESKTOP_VIEWPORT_WIDTH; } });
                } catch (e) {}

                var want = 'width=$DESKTOP_VIEWPORT_WIDTH';
                function forceViewport() {
                    try {
                        var meta = document.querySelector('meta[name=viewport]');
                        if (!meta) {
                            if (!document.head) return;
                            meta = document.createElement('meta');
                            meta.name = 'viewport';
                            document.head.appendChild(meta);
                        }
                        if (meta.getAttribute('content') !== want) {
                            meta.setAttribute('content', want);
                        }
                    } catch (e) {}
                }
                forceViewport();

                // Re-apply if the site injects or rewrites its own viewport meta
                function startObserver() {
                    if (!document.documentElement) return;
                    new MutationObserver(forceViewport).observe(document.documentElement, {
                        childList: true, subtree: true,
                        attributes: true, attributeFilter: ['content'],
                    });
                    forceViewport();
                }
                if (document.readyState === 'loading') {
                    document.addEventListener('DOMContentLoaded', startObserver);
                    startObserver();
                } else {
                    startObserver();
                }
            })();
        """.trimIndent()
        view.evaluateJavascript(js, null)
    }

    fun navigate(tabId: String, url: String) {
        // Explicit navigation is a fresh start — clear any crash-recovery budget.
        rendererFailures.remove(tabId)
        pendingHttpErrors.remove(tabId)
        pendingSslErrors.remove(tabId)
        clearErrorOnCommit.add(tabId)
        val safeUrl = sanitizeUrl(url)
        updateTab(tabId) {
            it.copy(
                url = safeUrl,
                isLoading = true,
                progress = 0,
                detectedMedia = emptyList(),
            )
        }
        urlInput = safeUrl
        liveViews[tabId]?.loadUrl(safeUrl)
    }

    fun goBack() {
        val id = activeTabId ?: return
        clearErrorOnCommit.remove(id)
        liveViews[id]?.goBack()
    }

    fun goForward() {
        val id = activeTabId ?: return
        clearErrorOnCommit.remove(id)
        liveViews[id]?.goForward()
    }

    fun reload() {
        val id = activeTabId ?: return
        // On an error page the toolbar button and the page's own Retry must agree.
        if (hasPageError(id)) retryTab(id) else liveViews[id]?.reload()
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

        val updatedList = MediaSnifferEngine.consolidateMediaTree(currentMedia + item)
        updateTab(tabId) { it.copy(detectedMedia = updatedList) }

        if (item.mediaType == com.rhnxdev.hzplayer.browser.media.MediaType.STREAM_HLS || item.url.contains(".m3u8") || item.isMasterStream) {
            scope.launch {
                val parsedItem = MediaSnifferEngine.parseHlsQualities(item)
                updateTab(tabId) { t ->
                    val newList = t.detectedMedia.map { m ->
                        if (m.id == item.id) parsedItem else m
                    }
                    t.copy(detectedMedia = MediaSnifferEngine.consolidateMediaTree(newList))
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
        if (liveViews[tabId] != null) {
            liveViews[tabId]?.destroy()
        }
        liveViews[tabId] = wv
        // A replacement instance starts from a blank document — no selection.
        if (tabId == activeTabId) resetElementPickerState()

        applySettingsToView(wv, settings)

        // Inject Media Sniffer JS bridge interface. The validator closes over
        // this tab's tokens, so calls from frames that never got the injected
        // script (cross-origin iframes) are dropped.
        wv.addJavascriptInterface(
            MediaSnifferBridge(
                tokenValidator = { token ->
                    val state = snifferTokens[tabId]
                    state != null && (token == state.current || token == state.previous)
                },
                onMediaDetected = { mediaUrl, pageTitle, mimeType, jsHeaders ->
                    val id = resolveTabId(wv) ?: return@MediaSnifferBridge
                    val enrichedHeaders = jsHeaders.toMutableMap()
                    val cookie = android.webkit.CookieManager.getInstance().getCookie(mediaUrl)
                    if (!cookie.isNullOrBlank() &&
                        enrichedHeaders.keys.none { it.equals("Cookie", ignoreCase = true) }
                    ) {
                        enrichedHeaders["Cookie"] = cookie
                    }
                    scope.launch(Dispatchers.Main) {
                        processCapturedMedia(id, mediaUrl, pageTitle, requestHeaders = enrichedHeaders, mimeType = mimeType)
                    }
                },
                onPlaybackStateChanged = { playing ->
                    scope.launch(Dispatchers.Main) {
                        resolveTabId(wv)?.let { setTabPlaying(it, playing) }
                    }
                },
                onPipRequested = {
                    scope.launch(Dispatchers.Main) {
                        if (resolveTabId(wv) == activeTabId) onPipRequested?.invoke()
                    }
                },
            ),
            MediaSnifferBridge.INTERFACE_NAME
        )

        wv.addJavascriptInterface(
            ElementPickerBridge { selector, matchCount, canWiden ->
                scope.launch(Dispatchers.Main) {
                    // A page that navigated away mid-tap can still call back — only
                    // the live picker on the active tab may drive the panel.
                    if (!elementPickerActive || resolveTabId(wv) != activeTabId) return@launch
                    pickedElement = PickedElement(selector, matchCount, canWiden)
                }
            },
            ElementPickerBridge.INTERFACE_NAME
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

                    val resourceType = when {
                        request.isForMainFrame -> "main_frame"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("script", ignoreCase = true) == true -> "script"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("image", ignoreCase = true) == true -> "image"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("style", ignoreCase = true) == true -> "stylesheet"
                        // Chromium sends "iframe"/"frame" for embedded frames (captcha
                        // widgets live in these) — map to subdocument so $subdocument
                        // filter rules AND their exceptions match instead of "other".
                        reqHeaders["Sec-Fetch-Dest"]?.equals("iframe", ignoreCase = true) == true -> "subdocument"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("frame", ignoreCase = true) == true -> "subdocument"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("document", ignoreCase = true) == true -> "subdocument"
                        reqHeaders["Sec-Fetch-Dest"]?.equals("empty", ignoreCase = true) == true -> "xmlhttprequest"
                        else -> "other"
                    }

                    if (AdBlockEngine.shouldBlockRequest(
                            requestUrl = urlStr,
                            pageUrl = pageUrl,
                            settings = settings,
                            resourceType = resourceType
                        )) {
                        return WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
                    }

                    if (MediaSnifferEngine.isMediaUrl(urlStr, reqHeaders)) {
                        if (tabId != null) {
                            val enriched = reqHeaders.toMutableMap()
                            val cookie = android.webkit.CookieManager.getInstance().getCookie(urlStr)
                            if (!cookie.isNullOrBlank() &&
                                enriched.keys.none { it.equals("Cookie", ignoreCase = true) }
                            ) {
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
                // New document — rotate the sniffer secret. The previous one stays
                // valid until this document commits (see onPageCommitVisible), so a
                // stopped or failed navigation doesn't orphan the visible page.
                snifferTokens[id] = SnifferToken(UUID.randomUUID().toString(), snifferTokens[id]?.current)
                // The new document has no picker script and no selection left.
                resetElementPickerState()
                // A fresh navigation supersedes the previous page's failure.
                pendingHttpErrors.remove(id)
                lastReportedProgress[id] = 0
                deferImages(view, id)
                val keepTabUrl = isSyntheticPageUrl(url)
                // The error page stays until a new document commits (see
                // onPageCommitVisible); clearing it here flashed the WebView's
                // own error document while the next page loaded.
                updateTab(id) {
                    it.copy(
                        url = if (keepTabUrl) it.url else url,
                        title = if (keepTabUrl) it.title else (view.title ?: ""),
                        icon = if (keepTabUrl) it.icon else favicon,
                        isLoading = true, progress = 0,
                        canGoBack = view.canGoBack(),
                        canGoForward = view.canGoForward(),
                        detectedMedia = emptyList(),
                    )
                }
                // Leave the bar alone while the user is typing in it.
                if (!keepTabUrl && id == activeTabId && !isUrlBarFocused) urlInput = url
                MediaSnifferBridge.injectSnifferJs(view, snifferTokenFor(id))
                if (isDesktopMode) injectDesktopModeJs(view)
            }

            override fun onPageFinished(view: WebView, url: String) {
                val id = resolveTabId(view) ?: return
                // A completed load means the renderer is healthy again.
                rendererFailures.remove(id)
                releaseImages(view, id)
                // The failing navigation finishes under its own URL, so this decides a
                // deferred certificate error. A finish for another host leaves it pending.
                pendingSslErrors[id]?.let { ssl ->
                    if (hostOf(url) == hostOf(ssl.url)) {
                        pendingSslErrors.remove(id)
                        reportPageError(id, ssl)
                    }
                }
                lastReportedProgress[id] = 100
                updateTab(id) {
                    it.copy(
                        title = view.title ?: "", isLoading = false, progress = 100,
                        canGoBack = view.canGoBack(), canGoForward = view.canGoForward(),
                    )
                }
                MediaSnifferBridge.injectSnifferJs(view, snifferTokenFor(id))
                if (isDesktopMode) injectDesktopModeJs(view)

                if (settings.adBlockEnabled && settings.cosmeticFilteringEnabled && url.isNotBlank()) {
                    val cosmeticCss = AdBlockEngine.getCosmeticCss(url, settings)
                    if (cosmeticCss.isNotBlank()) {
                        view.evaluateJavascript(cosmeticCssJs(cosmeticCss), null)
                    }
                }

                if (url.isNotBlank() && url != "about:blank" && !isSyntheticPageUrl(url) &&
                    !hasPageError(id)
                ) {
                    val pageTitle = view.title?.ifBlank { url } ?: url
                    onPageVisited?.invoke(url, pageTitle)
                }
                pendingHttpErrors.remove(id)?.let { showIfBlank(view, id, it) }
                onLoadFinished?.invoke()
            }


            override fun onPageCommitVisible(view: WebView, url: String) {
                // The new document has committed — the previous document is gone,
                // so retire its token.
                val id = resolveTabId(view) ?: return
                snifferTokens.computeIfPresent(id) { _, state -> state.copy(previous = null) }
                // The old document is no longer drawn, so the error page can go —
                // unless the committed document is the failure itself (its error
                // page restored from history), which must stay covered.
                val clear = clearErrorOnCommit.remove(id)
                val error = _tabs.value.find { it.id == id }?.error
                if (error != null && (clear || url != error.url)) {
                    updateTab(id) { it.copy(error = null) }
                }
            }

            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val urlStr = request.url.toString()
                if (handleExternalScheme(view, urlStr)) return true
                val id = resolveTabId(view)
                if (request.isForMainFrame && id == activeTabId && !isUrlBarFocused) {
                    urlInput = urlStr
                }
                return false
            }

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                if (handleExternalScheme(view, url)) return true
                if (resolveTabId(view) == activeTabId && !isUrlBarFocused) {
                    urlInput = url
                }
                return false
            }

            // ── Errors ─────────────────────────────────────────────────
            // Every failure goes through BrowserErrorMapper, then either
            // reportPageError (the page can't be shown) or notify (it can).

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: android.webkit.WebResourceError
            ) {
                // Failed sub-resources (images, scripts, trackers) are normal and
                // not worth interrupting the page for.
                if (!request.isForMainFrame) return
                val description = error.description?.toString()
                // A navigation the user (or a redirect) cancelled is not a failure.
                if (description?.contains("ERR_ABORTED") == true) return
                val id = resolveTabId(view) ?: return
                reportPageError(
                    id,
                    BrowserErrorMapper.fromWebResource(
                        errorCode = error.errorCode,
                        description = description,
                        url = request.url.toString(),
                        online = networkState.online,
                    ),
                )
            }

            override fun onReceivedHttpError(
                view: WebView,
                request: WebResourceRequest,
                errorResponse: android.webkit.WebResourceResponse
            ) {
                if (!request.isForMainFrame) return
                val id = resolveTabId(view) ?: return
                BrowserErrorMapper.fromHttpStatus(errorResponse.statusCode, request.url.toString())
                    ?.let { pendingHttpErrors[id] = it }
            }

            // The renderer is gone: this WebView instance is unusable, so drop it
            // and let the UI build a fresh one. Returning true is what keeps the
            // app process alive instead of being killed by the framework.
            override fun onRenderProcessGone(
                view: WebView?,
                detail: android.webkit.RenderProcessGoneDetail?
            ): Boolean {
                val crashed = detail?.didCrash() == true
                if (view == null) return true
                val tabId = resolveTabId(view)
                if (tabId != null) {
                    val url = _tabs.value.find { it.id == tabId }?.url.orEmpty()
                    discardDeadWebView(view, tabId, BrowserErrorMapper.fromRenderer(crashed, url))
                } else {
                    // Untracked WebView (e.g. a popup awaiting registration).
                    (view.parent as? ViewGroup)?.removeView(view)
                    try {
                        view.destroy()
                    } catch (_: Exception) {
                    }
                }
                return true
            }

            override fun onReceivedSslError(
                view: WebView?,
                handler: android.webkit.SslErrorHandler?,
                error: android.net.http.SslError?
            ) {
                // Always cancel — no "proceed anyway" bypass, matching a safety-first posture.
                handler?.cancel()
                val mapped = BrowserErrorMapper.fromSsl(error?.primaryError, error?.url.orEmpty())
                val id = view?.let { resolveTabId(it) } ?: return
                val pageUrl = view?.url ?: _tabs.value.find { it.id == id }?.url
                val errorHost = hostOf(mapped.url)
                val pageHost = hostOf(pageUrl)
                // Treat it as the page's own failure unless we can positively
                // identify it as a *different* host (a real embedded sub-resource).
                // SslError.getUrl() can be schemeless or blank, which previously
                // made the host comparison fail and the error vanish silently — a
                // genuine main-frame certificate failure must always surface.
                val isSubResource = errorHost != null && pageHost != null && errorHost != pageHost
                if (!isSubResource) {
                    reportPageError(id, mapped)
                } else {
                    pendingSslErrors[id] = mapped
                }
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
                if (currentUrl.isNotBlank() && currentUrl != "about:blank" &&
                    !isSyntheticPageUrl(currentUrl) && title.isNotBlank() && !hasPageError(id)
                ) {
                    onPageVisited?.invoke(currentUrl, title)
                }
            }

            override fun onProgressChanged(view: WebView, newProgress: Int) {
                val id = resolveTabId(view) ?: return
                reportProgress(id, newProgress)
                if (newProgress == 100) releaseImages(view, id)
                if (newProgress == 30 || newProgress == 60) {
                    MediaSnifferBridge.injectSnifferJs(view, snifferTokenFor(id))
                    if (isDesktopMode) injectDesktopModeJs(view)
                }
            }

            override fun onReceivedIcon(view: WebView, icon: Bitmap) {
                val id = resolveTabId(view) ?: return
                updateTab(id) { it.copy(icon = icon) }
            }

            // ── JavaScript dialogs ─────────────────────────────────────
            // Default WebChromeClient returns false for these, which makes
            // WebView silently auto-dismiss the dialog — breaking login gates,
            // confirm flows and "leave site?" prompts. Surface a real dialog.

            override fun onJsAlert(
                view: WebView, url: String?, message: String?, result: android.webkit.JsResult
            ): Boolean = showJsDialog(
                JsDialogRequest(JsDialogType.ALERT, url ?: "", message ?: "",
                    result = JsDialogResult.Confirm(result))
            )

            override fun onJsConfirm(
                view: WebView, url: String?, message: String?, result: android.webkit.JsResult
            ): Boolean = showJsDialog(
                JsDialogRequest(JsDialogType.CONFIRM, url ?: "", message ?: "",
                    result = JsDialogResult.Confirm(result))
            )

            override fun onJsPrompt(
                view: WebView, url: String?, message: String?, defaultValue: String?,
                result: android.webkit.JsPromptResult
            ): Boolean = showJsDialog(
                JsDialogRequest(JsDialogType.PROMPT, url ?: "", message ?: "",
                    defaultValue = defaultValue ?: "", result = JsDialogResult.Prompt(result))
            )

            override fun onJsBeforeUnload(
                view: WebView, url: String?, message: String?, result: android.webkit.JsResult
            ): Boolean = showJsDialog(
                JsDialogRequest(JsDialogType.BEFORE_UNLOAD, url ?: "", message ?: "",
                    result = JsDialogResult.Confirm(result))
            )

            // ── File upload (<input type="file">) ──────────────────────

            override fun onShowFileChooser(
                webView: WebView,
                filePathCallback: android.webkit.ValueCallback<Array<android.net.Uri>>,
                fileChooserParams: FileChooserParams
            ): Boolean {
                val handler = onShowFileChooser ?: return false
                // Only one picker at a time — releasing the previous callback with
                // null tells its page the earlier chooser was cancelled.
                fileChooserCallback?.onReceiveValue(null)
                fileChooserCallback = filePathCallback
                val accept = fileChooserParams.acceptTypes
                    ?.filter { it.isNotBlank() } ?: emptyList()
                handler(
                    FileChooserRequest(
                        acceptTypes = accept,
                        allowMultiple = fileChooserParams.mode ==
                            FileChooserParams.MODE_OPEN_MULTIPLE,
                        captureEnabled = fileChooserParams.isCaptureEnabled,
                        params = fileChooserParams,
                    )
                )
                return true
            }

            override fun onCreateWindow(
                view: WebView,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean {
                if (!settings.javaScriptEnabled) return false
                // A real tap (target="_blank", "Login with Google", etc.) always
                // opens a new tab, same as Chrome/Firefox/Brave — popup blocking
                // only applies to script-triggered windows with no user gesture
                // behind them (settings.javaScriptCanOpenWindows gates those).
                if (!isUserGesture && !settings.javaScriptCanOpenWindows) return false
                if (resultMsg == null) return false
                val parentUrl = view.url ?: ""
                // Remember opener tab so back can return to it when the popup closes
                val openerTabId = resolveTabId(view)

                val tempWebView = WebView(view.context)
                applySettingsToView(tempWebView, settings)

                var isEvaluated = false

                tempWebView.webViewClient = object : WebViewClient() {
                    /**
                     * Cross-domain pop-up: halt the temp WebView and hand it to the
                     * UI as a pending request so the user can Allow (→ opens as a new
                     * tab) or Deny (→ the WebView is destroyed). The WebView is kept
                     * ALIVE here — destroying it would strand the Allow path with
                     * nothing to register — so denyPendingPopup() owns teardown.
                     */
                    private fun promptCrossDomainPopup(v: WebView, popupUrl: String) {
                        v.post { v.stopLoading() }
                        onCrossDomainPopupRequested?.invoke(
                            PendingPopupRequest(
                                tempWebView = v,
                                parentUrl = parentUrl,
                                targetUrl = popupUrl,
                                targetDomain = getRootDomain(popupUrl),
                                sourceTabId = openerTabId,
                            )
                        )
                    }

                    override fun shouldOverrideUrlLoading(v: WebView, request: WebResourceRequest): Boolean {
                        val popupUrl = request.url?.toString() ?: ""
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, popupUrl)) {
                            isEvaluated = true
                            v.stopLoading()
                            promptCrossDomainPopup(v, popupUrl)
                            return true
                        }
                        if (!isEvaluated) {
                            isEvaluated = true
                            val newTabId = createTab(popupUrl, parentTabId = openerTabId)
                            registerWebView(newTabId, v)
                        }
                        return false
                    }

                    override fun onPageStarted(v: WebView, url: String, favicon: Bitmap?) {
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, url)) {
                            isEvaluated = true
                            v.stopLoading()
                            promptCrossDomainPopup(v, url)
                            return
                        }
                        if (!isEvaluated) {
                            isEvaluated = true
                            val newTabId = createTab(url, parentTabId = openerTabId)
                            registerWebView(newTabId, v)
                        }
                    }

                    override fun shouldInterceptRequest(v: WebView, request: WebResourceRequest): WebResourceResponse? {
                        val popupUrl = request.url?.toString() ?: ""
                        if (!isEvaluated && settings.blockCrossDomainPopups && isCrossDomain(parentUrl, popupUrl)) {
                            isEvaluated = true
                            promptCrossDomainPopup(v, popupUrl)
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
                updateTab(tabId) { it.copy(isLoading = true, progress = 0) }
                val restored = wv.restoreState(tab.savedState)
                if (restored != null) {
                    // One-shot handoff — drop it so a later re-registration
                    // (thaw / renderer crash) reloads the current URL instead
                    // of replaying this snapshot.
                    updateTab(tabId) { it.copy(savedState = null) }
                }
            } else if (tab.url.isNotBlank() && tab.error?.kind?.isRendererFailure != true) {
                // (A tab parked on a renderer-failure page waits for the user's Retry
                // instead of re-triggering the crash on every new WebView.)
                updateTab(tabId) { it.copy(isLoading = true, progress = 0) }
                wv.loadUrl(tab.url)
            }
        }
    }

    // ── Errors ───────────────────────────────────────────────────

    /**
     * The one way a page failure reaches the UI: records [error] on the tab, which
     * makes the error page cover the WebView. Everything that can fail a page ends here.
     */
    private fun reportPageError(tabId: String, error: BrowserError) {
        android.util.Log.w(
            "HzBrowser",
            "page error tab=$tabId kind=${error.kind} detail=${error.detail} url=${error.url}"
        )
        clearErrorOnCommit.remove(tabId)
        updateTab(tabId) { it.copy(error = error, isLoading = false) }
    }

    /** A failure that leaves the current page alone: shown as a brief notice. */
    private fun notify(error: BrowserError) {
        android.util.Log.w("HzBrowser", "notice kind=${error.kind} url=${error.url}")
        onNotice?.invoke(error)
    }

    /** The device has no usable WebView, so the tab can never load. */
    fun reportWebViewUnavailable(tabId: String) {
        val url = _tabs.value.find { it.id == tabId }?.url.orEmpty()
        reportPageError(tabId, BrowserError(BrowserErrorKind.WEBVIEW_UNAVAILABLE, url))
    }

    private fun hasPageError(tabId: String): Boolean =
        _tabs.value.find { it.id == tabId }?.error != null

    /**
     * Show [error] only if the finished page is visually empty. A server's own
     * 404/500 page is more informative than ours, so it wins when it has content.
     * A result of "null" (JavaScript off) is left alone rather than risk hiding content.
     */
    private fun showIfBlank(view: WebView, tabId: String, error: BrowserError) {
        view.evaluateJavascript(BLANK_PAGE_JS) { result ->
            if (result == "true" && liveViews[tabId] === view) reportPageError(tabId, error)
        }
    }

    /** Retry the active tab's failed page — what the error page's button calls. */
    fun retry() {
        val id = activeTabId ?: return
        retryTab(id)
    }

    /**
     * The page a Back press reveals over [tabId]'s error page: the URL the WebView still
     * shows, unless it is the failed URL, a data: page or blank. A cancelled navigation
     * leaves the previous page on screen with no back entry to return to.
     */
    private fun pageUnderError(tabId: String): String? {
        val error = _tabs.value.find { it.id == tabId }?.error ?: return null
        val shown = liveViews[tabId]?.url?.takeIf {
            it.isNotBlank() && it != "about:blank" && !isSyntheticPageUrl(it)
        } ?: return null
        return shown.takeIf { it != error.url }
    }

    /** True when Back should hide [tabId]'s error page and reveal the page under it. */
    fun canDismissPageError(tabId: String): Boolean = pageUnderError(tabId) != null

    /** Hides [tabId]'s error page and shows the page under it. Returns false when there is none. */
    fun dismissPageError(tabId: String): Boolean {
        val shown = pageUnderError(tabId) ?: return false
        val view = liveViews[tabId] ?: return false
        clearErrorOnCommit.remove(tabId)
        pendingSslErrors.remove(tabId)
        updateTab(tabId) {
            it.copy(
                error = null,
                url = shown,
                title = view.title?.takeIf { t -> t.isNotBlank() } ?: it.title,
                isLoading = false,
                canGoBack = view.canGoBack(),
                canGoForward = view.canGoForward(),
            )
        }
        if (tabId == activeTabId && !isUrlBarFocused) urlInput = shown
        return true
    }

    private fun retryTab(tabId: String) {
        val tab = _tabs.value.find { it.id == tabId } ?: return
        val view = liveViews[tabId]
        val url = tab.error?.url?.ifBlank { null } ?: tab.url
        if (view == null || url.isBlank()) return
        rendererFailures.remove(tabId)
        pendingHttpErrors.remove(tabId)
        // Keep the error set: the page must stay covered until the new document
        // commits. Clearing it here is what flashed the WebView's own error page.
        clearErrorOnCommit.add(tabId)
        updateTab(tabId) { it.copy(isLoading = true, progress = 0) }
        // loadUrl rather than reload(): after a failed load the WebView's history
        // entry is its own error document, and a fresh load is the reliable retry.
        view.loadUrl(url)
    }

    /** True when [tab] failed in a way that should reload itself once the network is back. */
    private fun autoRetryable(tab: BrowserTab): Boolean =
        settings.adaptiveNetworkEnabled && tab.error?.kind?.retry == RetryPolicy.ON_RECONNECT

    /**
     * Host of a URL, lowercased. Tolerates a missing scheme: `SslError.getUrl()`
     * can return a schemeless string (e.g. `expired.badssl.com/…`), which
     * `Uri.parse` would otherwise read as a path, yielding a null host and
     * silently dropping the certificate failure.
     */
    private fun hostOf(u: String?): String? {
        if (u.isNullOrBlank()) return null
        val withScheme = if (u.contains("://")) u else "https://$u"
        return runCatching { android.net.Uri.parse(withScheme).host?.lowercase() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
    }

    // ── Network adaptation ───────────────────────────────────────

    /**
     * React to a connectivity change: switch the cache policy for live views,
     * stop deferring images once the link is no longer constrained, and retry
     * loads that failed while the network was down.
     */
    fun onNetworkStateChanged(newState: BrowserNetworkState) {
        if (newState == networkState) return
        val wasOnline = networkState.online
        networkState = newState
        liveViews.values.forEach { it.settings.cacheMode = effectiveCacheMode(settings) }
        if (!newState.constrained) {
            liveViews.forEach { (id, view) -> releaseImages(view, id) }
        }
        if (!wasOnline && newState.online && settings.adaptiveNetworkEnabled) retryFailedLoads()
    }

    /** Reload the active tab if it failed on the network; others wait until [switchTab]. */
    private fun retryFailedLoads() {
        val id = activeTabId ?: return
        if (_tabs.value.find { it.id == id }?.let(::autoRetryable) == true) retryTab(id)
    }

    /**
     * Cache policy for new loads. With the default mode, a device that is offline
     * prefers cached copies (even expired) over an error page; explicit No cache /
     * Cache only choices are always honoured.
     */
    private fun effectiveCacheMode(s: BrowserSettings): Int = when (s.cacheMode) {
        BrowserCacheMode.NO_CACHE   -> android.webkit.WebSettings.LOAD_NO_CACHE
        BrowserCacheMode.CACHE_ONLY -> android.webkit.WebSettings.LOAD_CACHE_ONLY
        BrowserCacheMode.NORMAL     ->
            if (s.adaptiveNetworkEnabled && !networkState.online) {
                android.webkit.WebSettings.LOAD_CACHE_ELSE_NETWORK
            } else {
                android.webkit.WebSettings.LOAD_DEFAULT
            }
    }

    /**
     * On a slow or Data Saver link, hold back network images so text and layout
     * arrive first. They are fetched when the page finishes, or after
     * [IMAGE_DEFER_MAX_MS] at the latest, whichever comes first.
     */
    private fun deferImages(view: WebView, tabId: String) {
        imageDeferTimeouts.remove(tabId)?.cancel()
        if (!(settings.adaptiveNetworkEnabled && networkState.constrained)) {
            view.settings.blockNetworkImage = false
            return
        }
        view.settings.blockNetworkImage = true
        imageDeferTimeouts[tabId] = scope.launch {
            delay(IMAGE_DEFER_MAX_MS)
            imageDeferTimeouts.remove(tabId)
            // The view may have been replaced or destroyed while waiting.
            if (liveViews[tabId] === view) view.settings.blockNetworkImage = false
        }
    }

    /** Let a view fetch the images [deferImages] held back. */
    private fun releaseImages(view: WebView, tabId: String) {
        imageDeferTimeouts.remove(tabId)?.cancel()
        view.settings.blockNetworkImage = false
    }

    /**
     * Throttled progress write. Each write recomposes the whole browser screen,
     * so only whole steps (and the final 100) are pushed, not all ~100 callbacks.
     */
    private fun reportProgress(tabId: String, progress: Int) {
        val last = lastReportedProgress[tabId] ?: -1
        if (progress < 100 && progress >= last && progress - last < PROGRESS_STEP) return
        lastReportedProgress[tabId] = progress
        updateTab(tabId) { it.copy(progress = progress) }
    }

    /** Apply new settings to every live WebView and remember for future registrations. */
    @SuppressLint("SetJavaScriptEnabled")
    fun applySettings(newSettings: BrowserSettings) {
        // Like Chrome's "Desktop site" toggle, a UA/rendering mode change only
        // takes effect after the page is re-fetched — reload live views
        val customUaEdited = newSettings.userAgentMode == UserAgentMode.CUSTOM &&
            settings.customUserAgent != newSettings.customUserAgent
        val renderModeChanged = settings.userAgentMode != newSettings.userAgentMode ||
            customUaEdited ||
            // Darkening applies at style resolution — reload so the toggle takes
            // visible effect on the current page
            settings.darkWebContent != newSettings.darkWebContent
        settings = newSettings
        if (!newSettings.javaScriptEnabled) cancelElementPicker()
        liveViews.values.forEach { applySettingsToView(it, newSettings) }
        if (renderModeChanged) {
            val reload: suspend () -> Unit = { liveViews.values.forEach { it.reload() } }
            if (customUaEdited) {
                // The custom-UA field calls this per keystroke; coalesce so a
                // burst of edits re-fetches the live tabs once, after the pause.
                renderReloads.schedule(reload)
            } else {
                renderReloads.runNow(reload)
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun applySettingsToView(wv: WebView, s: BrowserSettings) {
        wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
        wv.settings.javaScriptEnabled                  = s.javaScriptEnabled
        wv.settings.javaScriptCanOpenWindowsAutomatically = s.javaScriptCanOpenWindows
        // Must be enabled whenever JS is on, independent of the "auto pop-up"
        // toggle above — this is what lets WebView create a new-window request
        // at all for target="_blank" links / window.open(). If it's off, WebView
        // doesn't call onCreateWindow; it swallows the request into the current
        // page (hijacking the origin tab) instead of opening a new one, even for
        // an ordinary user-tapped link. onCreateWindow's own isUserGesture check
        // is where automatic (gesture-less) pop-ups actually get blocked.
        wv.settings.setSupportMultipleWindows(s.javaScriptEnabled)
        wv.settings.domStorageEnabled                  = s.domStorageEnabled
        wv.settings.databaseEnabled                    = true
        wv.settings.mediaPlaybackRequiresUserGesture   = s.mediaPlaybackRequiresGesture
        wv.settings.loadsImagesAutomatically           = s.loadImagesAutomatically
        wv.settings.textZoom                           = s.textZoom
        // Desktop mode needs wide viewport + overview so the forced 1024px
        // layout is zoomed out to fit the screen, regardless of user layout prefs
        val desktop = s.userAgentMode == UserAgentMode.DESKTOP
        wv.settings.loadWithOverviewMode               = desktop || s.loadWithOverviewMode
        wv.settings.useWideViewPort                    = desktop || s.useWideViewPort
        if (desktop) {
            val screenWidthPx = wv.resources.displayMetrics.widthPixels
            wv.setInitialScale((screenWidthPx * 100) / DESKTOP_VIEWPORT_WIDTH)
        } else {
            wv.setInitialScale(0)   // WebView default
        }
        // Algorithmic darkening: WebView darkens pages that don't ship their own
        // dark styles. The app theme (Theme.HzPlayer) is dark, so this is
        // effective whenever the toggle is on.
        if (WebViewFeature.isFeatureSupported(WebViewFeature.ALGORITHMIC_DARKENING)) {
            WebSettingsCompat.setAlgorithmicDarkeningAllowed(wv.settings, s.darkWebContent)
        }
        wv.settings.builtInZoomControls                = s.builtInZoomEnabled
        wv.settings.displayZoomControls                = false
        wv.settings.setSupportZoom(true)
        wv.settings.allowFileAccess                    = true
        wv.settings.allowContentAccess                 = true
        // Deliberately left off (WebView default is already false): this browser
        // loads arbitrary remote pages, and allowing file:// content universal/
        // cross-origin access is a known WebView foot-gun — Chrome exposes no
        // equivalent capability to web content at all.
        @Suppress("DEPRECATION")
        wv.settings.allowFileAccessFromFileURLs        = false
        @Suppress("DEPRECATION")
        wv.settings.allowUniversalAccessFromFileURLs   = false
        wv.settings.mixedContentMode = if (s.blockMixedContent)
            android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
        else
            android.webkit.WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
        wv.settings.safeBrowsingEnabled                = s.safeBrowsingEnabled
        wv.settings.cacheMode = effectiveCacheMode(s)
        // Adaptive loading off → never leave images held back.
        if (!s.adaptiveNetworkEnabled) wv.settings.blockNetworkImage = false
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
        if (id == activeTabId) resetElementPickerState()
        val wv = liveViews[id] ?: return
        setTabPlaying(id, false)
        val bundle = android.os.Bundle()
        wv.saveState(bundle)
        updateTab(id) { it.copy(savedState = bundle) }
        wv.stopLoading()
        wv.onPause()
        (wv.parent as? ViewGroup)?.removeView(wv)
        wv.destroy()
        liveViews.remove(id)
        snifferTokens.remove(id)
        clearNetworkState(id)
    }

    /** Get the WebView for a specific tab (null if frozen or not yet created). */
    fun getWebView(tabId: String): WebView? = liveViews[tabId]

    /** Destroy a tab's WebView and forget its generation (tab closed / torn down). */
    private fun dropTab(id: String) {
        liveViews.remove(id)?.destroy()
        webViewGenerations.value = webViewGenerations.value - id
        rendererFailures.remove(id)
        snifferTokens.remove(id)
        clearNetworkState(id)
    }

    /** Forget a tab's network-adaptation bookkeeping (its view is gone or replaced). */
    private fun clearNetworkState(id: String) {
        imageDeferTimeouts.remove(id)?.cancel()
        pendingHttpErrors.remove(id)
        pendingSslErrors.remove(id)
        lastReportedProgress.remove(id)
        clearErrorOnCommit.remove(id)
    }

    /**
     * A WebView whose render process died can't be used again — the framework
     * requires it to be removed from the hierarchy with all references dropped.
     * Discard it and bump the generation so the UI builds a fresh instance,
     * which reloads the tab's current URL instead of stranding it on a blank page.
     *
     * After [MAX_RENDERER_RECOVERIES] consecutive failures the page is not
     * reloaded again; [error] is shown instead, with a Retry button.
     */
    private fun discardDeadWebView(dead: WebView, tabId: String, error: BrowserError) {
        if (tabId == activeTabId) resetElementPickerState()
        liveViews.remove(tabId)
        snifferTokens.remove(tabId)
        imageDeferTimeouts.remove(tabId)?.cancel()
        lastReportedProgress.remove(tabId)
        pendingHttpErrors.remove(tabId)
        setTabPlaying(tabId, false)
        (dead.parent as? ViewGroup)?.removeView(dead)
        try {
            dead.destroy()
        } catch (_: Exception) {
            // Renderer is already gone — nothing left to release.
        }

        val failures = (rendererFailures[tabId] ?: 0) + 1
        rendererFailures[tabId] = failures
        if (failures > MAX_RENDERER_RECOVERIES) {
            // This page keeps killing the renderer — stop auto-reloading it so we
            // don't loop; park the tab on the error page for the user to act on.
            reportPageError(tabId, error)
        }

        webViewGenerations.value = webViewGenerations.value + (tabId to generationOf(tabId) + 1)
    }

    // ── Lifecycle ────────────────────────────────────────────────

    fun pause() {
        activeTabId?.let { liveViews[it]?.onPause() }
    }

    fun resume() {
        activeTabId?.let { liveViews[it]?.onResume() }
    }

    fun destroy() {
        // Release any modal still awaiting a result so its blocked page thread
        // doesn't linger, and so no ghost dialog survives into a new session.
        jsDialog?.let { resolveJsDialog(confirmed = false) }
        deliverFileChooserResult(null)
        resetElementPickerState()
        renderReloads.cancel()
        imageDeferTimeouts.values.forEach { it.cancel() }
        imageDeferTimeouts.clear()
        pendingHttpErrors.clear()
        pendingSslErrors.clear()
        lastReportedProgress.clear()
        clearErrorOnCommit.clear()
        liveViews.values.forEach { it.destroy() }
        liveViews.clear()
        snifferTokens.clear()
        webViewGenerations.value = emptyMap()
        rendererFailures.clear()
        _tabs.value = emptyList()
        activeTabId = null
        urlInput = ""
    }

    // ── Internals ────────────────────────────────────────────────

    /**
     * The tab's live sniffer secret, created on first use.
     * ConcurrentHashMap because the bridge validator reads it on the JavaBridge thread.
     */
    private fun snifferTokenFor(tabId: String): String =
        snifferTokens.computeIfAbsent(tabId) { SnifferToken(UUID.randomUUID().toString()) }.current

    private fun resolveTabId(view: WebView): String? {
        return liveViews.entries.firstOrNull { it.value == view }?.key
    }

    private fun activeWebView(): WebView? = activeTabId?.let { liveViews[it] }

    /**
     * Picker mode must never outlive its document: reset wherever the page or the
     * WebView goes away, otherwise the panel would keep pointing at a dead element.
     */
    private fun resetElementPickerState() {
        elementPickerActive = false
        pickedElement = null
    }

    /** Only real http(s) documents can be inspected — file:/data: pages are pointless. */
    private fun isPickableUrl(url: String?): Boolean =
        !url.isNullOrBlank() &&
            (url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true))

    private fun updateTab(id: String, transform: (BrowserTab) -> BrowserTab) {
        val current = _tabs.value
        val index = current.indexOfFirst { it.id == id }
        if (index < 0) return
        val updated = transform(current[index])
        // Skip no-op writes — every _tabs write recomposes the whole browser
        // screen (WebView host included), so duplicate callbacks (progress,
        // title, icon) must not churn it.
        if (updated == current[index]) return
        _tabs.value = current.toMutableList().also { it[index] = updated }
    }

    private fun isSyntheticPageUrl(url: String?): Boolean =
        url != null && url.startsWith("data:text/html")

    /** Schemes WebView cannot render itself — must be handed off to another app. */
    private val externalSchemes = listOf(
        "tel:", "mailto:", "sms:", "smsto:", "geo:", "market:", "intent:",
    )

    /**
     * Chrome/Firefox/Brave all intercept non-http(s) links (phone numbers, email,
     * SMS, map coordinates, Play Store, custom app deep links) and hand them off
     * to the matching Android app instead of letting WebView fail to load them.
     * Returns true if the URL was handled (navigation should be cancelled).
     */
    private fun handleExternalScheme(view: WebView, url: String): Boolean {
        val isIntent = url.startsWith("intent:", ignoreCase = true)
        val isExternal = isIntent || externalSchemes.any { url.startsWith(it, ignoreCase = true) }
        if (!isExternal) return false

        // intent:// is the scheme malicious/ad pages abuse most to jump apps
        // (or the Play Store) without pop-up-blocker or same-origin checks
        // applying — let the user turn it off while keeping tel:/mailto:/etc.
        if (isIntent && !settings.allowIntentLinks) {
            notify(BrowserErrorMapper.intentLinkBlocked(url))
            return true
        }

        try {
            val intent = if (isIntent) {
                android.content.Intent.parseUri(url, android.content.Intent.URI_INTENT_SCHEME)
            } else {
                android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(url))
            }
            intent.addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            view.context.startActivity(intent)
        } catch (_: Exception) {
            // ActivityNotFoundException, or an intent:// URI that doesn't parse.
            notify(BrowserErrorMapper.noAppForLink(url))
        }
        return true
    }

    private fun sanitizeUrl(input: String): String {
        val trimmed = input.trim()
        if (trimmed.isBlank()) return "about:blank"
        // "chrome:" lets WebView's internal test pages (chrome://crash, which
        // exercises renderer-death recovery) be reached from the URL bar.
        val knownSchemes = listOf(
            "about:", "file:", "data:", "javascript:", "blob:", "mailto:", "tel:", "chrome:",
        )
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

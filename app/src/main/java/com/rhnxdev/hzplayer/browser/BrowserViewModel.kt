package com.rhnxdev.hzplayer.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.BrowserHistoryItem
import com.rhnxdev.hzplayer.domain.model.UrlSuggestion
import com.rhnxdev.hzplayer.domain.repository.BrowserHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import com.rhnxdev.hzplayer.browser.adblock.AdBlockEngine
import com.rhnxdev.hzplayer.browser.adblock.AdBlockUpdater
import com.rhnxdev.hzplayer.browser.adblock.CosmeticRuleBuilder
import com.rhnxdev.hzplayer.browser.adblock.PickedElement
import com.rhnxdev.hzplayer.core.util.DebouncedAction
import com.rhnxdev.hzplayer.core.util.withoutScheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

/** Quiet period after the last custom-rule keystroke before the adblock engine rebuilds. */
private const val AD_BLOCK_RULE_DEBOUNCE_MS = 500L

/**
 * Transient warning raised by a WebView callback. Carries data only — the UI
 * resolves it to a string so no user-facing text lives in the ViewModel.
 */
sealed interface BrowserWarning {
    /** A tel:/mailto:/market: link had no app to handle it. */
    data object NoAppForLink : BrowserWarning

    /** An intent:// link was dropped because the user disabled app links. */
    data object IntentLinkBlocked : BrowserWarning

    /** @param primaryError one of the `SslError.SSL_*` codes (null = unreported), mapped to text by the UI. */
    data class SslErrorBlocked(val host: String, val primaryError: Int?) : BrowserWarning
}

/** Outcome of a manual ad-block filter-list refresh, shown in the settings screen. */
sealed interface AdBlockStatus {
    data class Updated(val ruleCount: Int) : AdBlockStatus
    data object Failed : AdBlockStatus
}

@HiltViewModel
class BrowserViewModel @Inject constructor(
    application: Application,
    private val historyRepository: BrowserHistoryRepository,
) : AndroidViewModel(application) {

    /** The initial URL to load (from intent). */
    var initialUrl: String = ""

    private val settingsStore = BrowserSettingsStore.get(application)
    private val sessionStore = BrowserSessionStore.get(application)

    // ── Settings state ────────────────────────────────────────────

    var settings by mutableStateOf(settingsStore.load())
        private set

    val tabManager = TabManager(initialSettings = settings)

    // ── History state ────────────────────────────────────────────

    private val _historySearchQuery = MutableStateFlow("")
    val historySearchQuery: StateFlow<String> = _historySearchQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val historyItems: StateFlow<List<BrowserHistoryItem>> = _historySearchQuery
        .flatMapLatest { query ->
            if (query.isBlank()) {
                historyRepository.getAllHistory()
            } else {
                historyRepository.searchHistory(query)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    private var lastRecordedUrl = ""
    private var lastRecordedTime = 0L

    // ── URL bar (omnibox) suggestions ────────────────────────────

    /**
     * True while the top URL bar has input focus. Owned by [TabManager] so the
     * WebView navigation callbacks can avoid clobbering what the user is typing.
     */
    val isUrlBarFocused: Boolean get() = tabManager.isUrlBarFocused

    // null = suggestions hidden; "" = show most visited; otherwise substring filter
    private val _urlSuggestionQuery = MutableStateFlow<String?>(null)
    val urlSuggestionQuery: StateFlow<String?> = _urlSuggestionQuery.asStateFlow()

    @OptIn(ExperimentalCoroutinesApi::class)
    val urlSuggestions: StateFlow<List<UrlSuggestion>> = _urlSuggestionQuery
        .flatMapLatest { query ->
            if (query == null) {
                flowOf(emptyList())
            } else {
                historyRepository.getUrlSuggestions(query, limit = 10)
            }
        }
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList(),
        )

    fun onUrlBarFocusChanged(focused: Boolean) {
        tabManager.isUrlBarFocused = focused
        if (focused) {
            urlBarEdited = false
        } else if (!urlBarEdited) {
            // While focused the navigation callbacks deliberately stopped
            // updating the bar, so re-sync it rather than leave a stale URL.
            activeTab?.url?.let { urlInput = it }
        }
        // Chrome-style: on focus show most visited sites, filter once typing starts
        _urlSuggestionQuery.value = if (focused) "" else null
    }

    /** True once the user has edited the URL bar since it gained focus. */
    private var urlBarEdited = false

    fun onUrlInputChanged(value: String) {
        urlInput = value
        urlBarEdited = true
        if (isUrlBarFocused) {
            // Strip scheme so matching works against both url and title
            _urlSuggestionQuery.value = value.withoutScheme().trim()
        }
    }

    var popupWarning by mutableStateOf<BrowserWarning?>(null)
        private set

    var pendingPopupRequest by mutableStateOf<PendingPopupRequest?>(null)
        private set

    fun clearPopupWarning() {
        popupWarning = null
    }

    fun allowPendingPopup() {
        val req = pendingPopupRequest ?: return
        val newTabId = tabManager.createTab(req.targetUrl, parentTabId = req.sourceTabId)
        if (req.tempWebView != null) {
            tabManager.registerWebView(newTabId, req.tempWebView)
        }
        tabManager.switchTab(newTabId)
        saveSessionIfEnabled()
        pendingPopupRequest = null
    }

    fun denyPendingPopup() {
        val req = pendingPopupRequest ?: return
        try {
            req.tempWebView?.stopLoading()
            req.tempWebView?.destroy()
        } catch (_: Exception) {}
        pendingPopupRequest = null
    }

    var isAdBlockUpdating by mutableStateOf(false)
        private set

    var adBlockStatus by mutableStateOf<AdBlockStatus?>(null)
        private set

    /** Coalesces per-keystroke custom-rule edits into one off-main engine rebuild. */
    private val adBlockReloads = DebouncedAction(viewModelScope, AD_BLOCK_RULE_DEBOUNCE_MS)

    init {
        tabManager.onTabSwitched = { saveSessionIfEnabled() }
        // Apply cookie settings from persisted prefs on startup
        tabManager.applyCookieSettings(settings)

        // Initialize AdBlock Engine with stored settings
        AdBlockEngine.initialize(application, settings)

        // Background async update filter lists on startup if adblock is enabled
        if (settings.adBlockEnabled) {
            viewModelScope.launch(Dispatchers.IO) {
                AdBlockUpdater.updateLists(application, settings.enabledFilterLists)
                val updated = settings.copy(lastAdBlockUpdateTimestamp = System.currentTimeMillis())
                // Rebuild off the main thread — it re-parses every list fetched
                AdBlockEngine.reload(application, updated)
                withContext(Dispatchers.Main) {
                    settings = updated
                    settingsStore.save(updated)
                }
            }
        }

        tabManager.onExternalSchemeFailed = { popupWarning = BrowserWarning.NoAppForLink }

        tabManager.onIntentLinkBlocked = { popupWarning = BrowserWarning.IntentLinkBlocked }

        tabManager.onSslErrorBlocked = { host, primaryError ->
            popupWarning = BrowserWarning.SslErrorBlocked(host, primaryError)
        }

        tabManager.onCrossDomainPopupRequested = { request ->
            pendingPopupRequest = request
        }

        tabManager.onPageVisited = { url, title ->
            val now = System.currentTimeMillis()
            if (url != lastRecordedUrl || (now - lastRecordedTime) > 2000) {
                lastRecordedUrl = url
                lastRecordedTime = now
                viewModelScope.launch {
                    historyRepository.addHistory(url = url, title = title, timestamp = now)
                }
            }
            saveSessionIfEnabled()
        }

        // BrowserScreen initializes after BrowserActivity has assigned initialUrl.
    }

    fun refreshAdBlockFilters() {
        if (isAdBlockUpdating) return
        isAdBlockUpdating = true
        adBlockStatus = null
        viewModelScope.launch(Dispatchers.IO) {
            val result = AdBlockUpdater.updateLists(getApplication(), settings.enabledFilterLists)
            val updatedSettings = settings.copy(lastAdBlockUpdateTimestamp = System.currentTimeMillis())
            // Rebuild off the main thread — it re-parses every list fetched
            AdBlockEngine.reload(getApplication(), updatedSettings)
            withContext(Dispatchers.Main) {
                settings = updatedSettings
                settingsStore.save(updatedSettings)
                isAdBlockUpdating = false
                adBlockStatus = when (result) {
                    is AdBlockUpdater.UpdateResult.Success -> AdBlockStatus.Updated(AdBlockEngine.totalRuleCount)
                    is AdBlockUpdater.UpdateResult.Error -> AdBlockStatus.Failed
                }
            }
        }
    }

    fun updateHistorySearchQuery(query: String) {
        _historySearchQuery.value = query
    }

    fun deleteHistoryItem(id: Long) {
        viewModelScope.launch {
            historyRepository.deleteHistoryItem(id)
        }
    }

    fun clearAllHistory() {
        viewModelScope.launch {
            historyRepository.clearAllHistory()
        }
    }

    fun updateSettings(newSettings: BrowserSettings) {
        val oldSettings = settings
        settings = newSettings
        settingsStore.save(newSettings)
        tabManager.applySettings(newSettings)
        tabManager.applyCookieSettings(newSettings)
        saveSessionIfEnabled()

        if (oldSettings.adBlockEnabled != newSettings.adBlockEnabled ||
            oldSettings.enabledFilterLists != newSettings.enabledFilterLists ||
            oldSettings.customAdBlockRules != newSettings.customAdBlockRules ||
            oldSettings.cosmeticFilteringEnabled != newSettings.cosmeticFilteringEnabled
        ) {
            // The rules field calls this per keystroke — coalesce those edits
            // into one rebuild; a toggle applies at once. Both run off Main.
            val reload: suspend () -> Unit = {
                withContext(Dispatchers.IO) { AdBlockEngine.reload(getApplication(), newSettings) }
            }
            if (oldSettings.customAdBlockRules != newSettings.customAdBlockRules) {
                adBlockReloads.schedule(reload)
            } else {
                adBlockReloads.runNow(reload)
            }
        }
    }

    /** True when the browser renders pages with full desktop mode. */
    val isDesktopMode: Boolean
        get() = settings.userAgentMode == UserAgentMode.DESKTOP

    /** Chrome-style "Desktop site" toggle — switches rendering mode and reloads live tabs. */
    fun toggleDesktopMode() {
        val newMode = if (isDesktopMode) UserAgentMode.MOBILE else UserAgentMode.DESKTOP
        updateSettings(settings.copy(userAgentMode = newMode))
    }

    // ── Element picker ("block element") ──────────────────────────

    /** True when the active tab is a real, scriptable page the picker can inspect. */
    val canPickElements: Boolean get() = tabManager.canStartElementPicker
    val isElementPickerActive: Boolean get() = tabManager.elementPickerActive
    val pickedElement: PickedElement? get() = tabManager.pickedElement

    fun startElementPicker() = tabManager.startElementPicker()

    fun cancelElementPicker() = tabManager.cancelElementPicker()

    fun widenElementSelection() = tabManager.widenElementSelection()

    /**
     * Hide the picked element and persist a cosmetic rule for it. Hiding already
     * happened in the page; this only saves the rule and rebuilds the engine.
     */
    fun blockPickedElement(applyToAllSites: Boolean) {
        val picked = tabManager.applyPickedHideAndExit() ?: return
        val rule = CosmeticRuleBuilder.build(hostOf(activeTab?.url), picked.selector, applyToAllSites)
            ?: return
        val current = settings.customAdBlockRules
        if (CosmeticRuleBuilder.contains(current, rule)) return
        updateSettings(settings.copy(customAdBlockRules = CosmeticRuleBuilder.append(current, rule)))
    }

    private fun hostOf(url: String?): String? =
        url?.let { runCatching { android.net.Uri.parse(it).host }.getOrNull() }

    fun initialize() {
        if (tabManager.tabs.isNotEmpty()) return

        if (settings.restoreTabsOnStartup) {
            val restored = sessionStore.loadSession()
            if (restored != null && restored.first.isNotEmpty()) {
                tabManager.restoreSession(restored.first, restored.second)
                if (initialUrl.isNotBlank()) {
                    val active = tabManager.tabs.find { it.id == tabManager.activeTabId }
                    if (active == null || active.url.isBlank() || active.url == "about:blank") {
                        val targetId = tabManager.activeTabId ?: tabManager.createTab(initialUrl)
                        tabManager.navigate(targetId, initialUrl)
                    } else {
                        tabManager.createTab(initialUrl)
                    }
                }
                saveSessionIfEnabled()
                return
            }
        }

        tabManager.createTab(initialUrl.ifBlank { "about:blank" })
        saveSessionIfEnabled()
    }

    private fun saveSessionIfEnabled() {
        if (settings.restoreTabsOnStartup) {
            sessionStore.saveSession(tabManager.tabs, tabManager.activeTabId)
        } else {
            sessionStore.clearSession()
        }
    }

    // ── Delegated state ──────────────────────────────────────────

    val tabs: List<BrowserTab> get() = tabManager.tabs
    val activeTabId: String? get() = tabManager.activeTabId
    var urlInput: String
        get() = tabManager.urlInput
        set(value) { tabManager.urlInput = value }

    // ── Actions ──────────────────────────────────────────────────

    fun createTab(url: String = ""): String {
        val id = tabManager.createTab(url)
        saveSessionIfEnabled()
        return id
    }

    fun closeTab(id: String) {
        tabManager.closeTab(id)
        saveSessionIfEnabled()
    }

    fun switchTab(id: String) {
        tabManager.switchTab(id)
        saveSessionIfEnabled()
    }

    fun switchToNextTab() {
        val tabList = tabManager.tabs
        if (tabList.size <= 1) return
        val currentIdx = tabList.indexOfFirst { it.id == tabManager.activeTabId }
        if (currentIdx < 0) return
        val nextIdx = (currentIdx + 1) % tabList.size
        tabManager.switchTab(tabList[nextIdx].id)
        saveSessionIfEnabled()
    }

    fun switchToPreviousTab() {
        val tabList = tabManager.tabs
        if (tabList.size <= 1) return
        val currentIdx = tabList.indexOfFirst { it.id == tabManager.activeTabId }
        if (currentIdx < 0) return
        val prevIdx = if (currentIdx == 0) tabList.size - 1 else currentIdx - 1
        tabManager.switchTab(tabList[prevIdx].id)
        saveSessionIfEnabled()
    }

    fun navigate(url: String) {
        val id = tabManager.activeTabId ?: createTab(url)
        if (activeTabId != null) {
            tabManager.navigate(id, url)
        }
        saveSessionIfEnabled()
    }

    fun goBack() = tabManager.goBack()
    fun goForward() = tabManager.goForward()
    fun reload() = tabManager.reload()
    fun stopLoading() = tabManager.stopLoading()

    /** True when the active tab is a popup whose opener tab is still alive. */
    val canReturnToParentTab: Boolean
        get() {
            val parentId = activeTab?.parentTabId ?: return false
            return tabs.any { it.id == parentId }
        }

    /**
     * Chrome-like back: navigate the WebView history if possible; otherwise,
     * if this tab was opened as a popup, close it and return to the opener tab.
     */
    fun goBackOrClosePopup() {
        val tab = activeTab ?: return
        if (tab.canGoBack) {
            tabManager.goBack()
            return
        }
        if (canReturnToParentTab) {
            tabManager.closeTab(tab.id)   // closeTab switches back to the parent
            saveSessionIfEnabled()
        }
    }

    val activeTab: BrowserTab?
        get() = tabs.find { it.id == activeTabId }

    val tabCount: Int get() = tabs.size

    val activeTabMediaItems: List<com.rhnxdev.hzplayer.browser.media.DetectedMediaItem>
        get() = activeTab?.detectedMedia ?: emptyList()

    val activeTabMediaCount: Int
        get() = activeTabMediaItems.size

    fun clearActiveTabMedia() {
        activeTabId?.let { tabManager.clearMediaForTab(it) }
    }

    fun updateMediaQuality(itemId: String, qualityUrl: String) {
        activeTabId?.let { tabManager.updateSelectedMediaQuality(it, itemId, qualityUrl) }
    }


    // ── Lifecycle ────────────────────────────────────────────────

    override fun onCleared() {
        super.onCleared()
        tabManager.destroy()
    }

    fun onPause() {
        tabManager.pause()
        saveSessionIfEnabled()
    }

    fun onResume() = tabManager.resume()
}

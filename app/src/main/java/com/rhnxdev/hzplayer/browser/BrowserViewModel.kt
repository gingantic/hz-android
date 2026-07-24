package com.rhnxdev.hzplayer.browser

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.BrowserHistoryItem
import com.rhnxdev.hzplayer.domain.repository.BrowserHistoryRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import com.rhnxdev.hzplayer.browser.adblock.AdBlockEngine
import com.rhnxdev.hzplayer.browser.adblock.AdBlockUpdater
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import javax.inject.Inject

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

    var popupWarningMessage by mutableStateOf<String?>(null)
        private set

    var pendingPopupRequest by mutableStateOf<PendingPopupRequest?>(null)
        private set

    fun clearPopupWarning() {
        popupWarningMessage = null
    }

    fun allowPendingPopup() {
        val req = pendingPopupRequest ?: return
        val newTabId = tabManager.createTab(req.targetUrl)
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

    var adBlockStatusMessage by mutableStateOf<String?>(null)
        private set

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
                val now = System.currentTimeMillis()
                withContext(Dispatchers.Main) {
                    val updated = settings.copy(lastAdBlockUpdateTimestamp = now)
                    settings = updated
                    settingsStore.save(updated)
                    AdBlockEngine.reload(application, updated)
                }
            }
        }

        tabManager.onCrossDomainPopupBlocked = { _, blockedDomain ->
            popupWarningMessage = "Blocked cross-domain pop-up ($blockedDomain)"
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
    }

    fun refreshAdBlockFilters() {
        if (isAdBlockUpdating) return
        isAdBlockUpdating = true
        adBlockStatusMessage = "Updating filter lists..."
        viewModelScope.launch(Dispatchers.IO) {
            val result = AdBlockUpdater.updateLists(getApplication(), settings.enabledFilterLists)
            val now = System.currentTimeMillis()
            val updatedSettings = settings.copy(lastAdBlockUpdateTimestamp = now)
            withContext(Dispatchers.Main) {
                settings = updatedSettings
                settingsStore.save(updatedSettings)
                AdBlockEngine.reload(getApplication(), updatedSettings)
                isAdBlockUpdating = false
                adBlockStatusMessage = when (result) {
                    is AdBlockUpdater.UpdateResult.Success -> "Updated successfully (${AdBlockEngine.totalRuleCount} active rules)"
                    is AdBlockUpdater.UpdateResult.Error -> result.message
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
            AdBlockEngine.reload(getApplication(), newSettings)
        }
    }

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

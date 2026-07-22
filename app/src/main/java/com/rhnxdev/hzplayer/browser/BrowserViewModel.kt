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
import kotlinx.coroutines.launch
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

    init {
        tabManager.onTabSwitched = { saveSessionIfEnabled() }
        // Apply cookie settings from persisted prefs on startup
        tabManager.applyCookieSettings(settings)

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
        settings = newSettings
        settingsStore.save(newSettings)
        tabManager.applySettings(newSettings)
        tabManager.applyCookieSettings(newSettings)
        saveSessionIfEnabled()
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

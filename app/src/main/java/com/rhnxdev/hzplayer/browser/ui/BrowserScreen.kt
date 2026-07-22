package com.rhnxdev.hzplayer.browser.ui

import android.app.Activity
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.browser.BrowserViewModel

@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    var showTabSidebar by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }

    val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
    val historySearchQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()

    // Back: close overlays first (history → settings → sidebar), then navigate back or exit
    BackHandler(enabled = true) {
        when {
            showHistory    -> showHistory = false
            showSettings   -> showSettings = false
            showTabSidebar -> showTabSidebar = false
            viewModel.activeTab?.canGoBack == true -> viewModel.goBack()
            else -> (context as? Activity)?.finish()
        }
    }

    LaunchedEffect(Unit) {
        viewModel.initialize()
    }

    // Nav bar gets solid surfaceContainerHigh to match bottom toolbar
    val navColor = MaterialTheme.colorScheme.surfaceContainerHigh.toArgb()
    val currentView = LocalView.current
    SideEffect {
        val window = (currentView.context as? Activity)?.window ?: return@SideEffect
        window.navigationBarColor = navColor
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding()
    ) {
        // Main content — WebView + bottom bar
        Column(modifier = Modifier.fillMaxSize()) {
            // WebView content (always visible for active tab)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                val activeId = viewModel.activeTabId
                val activeTab = viewModel.tabs.find { it.id == activeId }
                val showNewTab = activeId == null || activeTab == null ||
                        activeTab.url.isBlank() || activeTab.url == "about:blank"

                if (activeId != null) {
                    BrowserWebView(
                        viewModel = viewModel,
                        tabId = activeId,
                    )
                }

                if (showNewTab) {
                    NewTabPage(
                        onUrlEntered = { url ->
                            if (url.isNotBlank()) {
                                if (activeId != null) viewModel.navigate(url)
                                else viewModel.createTab(url)
                            }
                        },
                    )
                }
            }

            // Bottom toolbar
            BrowserBottomBar(
                url = viewModel.urlInput,
                currentTabUrl = viewModel.activeTab?.url ?: "",
                canGoBack = viewModel.activeTab?.canGoBack == true,
                canGoForward = viewModel.activeTab?.canGoForward == true,
                tabCount = viewModel.tabCount,
                isLoading = viewModel.activeTab?.isLoading == true,
                onBack = { viewModel.goBack() },
                onForward = { viewModel.goForward() },
                onReload = { viewModel.reload() },
                onStopLoading = { viewModel.stopLoading() },
                onUrlChange = { viewModel.urlInput = it },
                onUrlSubmit = { viewModel.navigate(viewModel.urlInput) },
                onNewTab = { viewModel.createTab() },
                onTabsClick = { showTabSidebar = true },
                onMenuClick = { /* Handled inside bottom bar 3-dot dropdown */ },
                onHistoryClick = { showHistory = true },
                onSettingsClick = { showSettings = true },
                modifier = Modifier.fillMaxWidth(),
            )
        }

        // Left-side tab sidebar overlay
        TabSidebar(
            visible = showTabSidebar,
            tabs = viewModel.tabs,
            activeTabId = viewModel.activeTabId,
            onTabClick = { viewModel.switchTab(it) },
            onTabClose = { viewModel.closeTab(it) },
            onNewTab = { viewModel.createTab() },
            onDismiss = { showTabSidebar = false },
        )

        // Browser history overlay
        BrowserHistoryScreen(
            visible = showHistory,
            historyItems = historyItems,
            searchQuery = historySearchQuery,
            onSearchQueryChange = { viewModel.updateHistorySearchQuery(it) },
            onItemClick = { url -> viewModel.navigate(url) },
            onDeleteItem = { viewModel.deleteHistoryItem(it) },
            onClearAll = { viewModel.clearAllHistory() },
            onDismiss = { showHistory = false },
        )

        // Browser settings overlay
        BrowserSettingsScreen(
            visible = showSettings,
            settings = viewModel.settings,
            onSave = { viewModel.updateSettings(it) },
            onDismiss = { showSettings = false },
        )
    }
}

@Composable
private fun BrowserWebView(
    viewModel: BrowserViewModel,
    tabId: String,
) {
    key(tabId) {
        val context = LocalContext.current
        val webView = remember(tabId) {
            val wv = viewModel.tabManager.getWebView(tabId) ?: WebView(context)
            wv.layoutParams = android.view.ViewGroup.LayoutParams(
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
                android.view.ViewGroup.LayoutParams.MATCH_PARENT,
            )
            viewModel.tabManager.registerWebView(tabId, wv)
            viewModel.tabManager.trimPool(tabId)
            wv
        }

        val targetUrl = viewModel.tabs.find { it.id == tabId }?.url ?: ""

        AndroidView(
            factory = {
                (webView.parent as? android.view.ViewGroup)?.removeView(webView)
                webView
            },
            update = { wv ->
                if (targetUrl.isNotBlank() && targetUrl != "about:blank" && wv.url != targetUrl) {
                    wv.loadUrl(targetUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

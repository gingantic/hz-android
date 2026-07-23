package com.rhnxdev.hzplayer.browser.ui

import android.app.Activity
import android.widget.Toast
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.browser.BrowserViewModel

import android.view.MotionEvent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imeAnimationTarget
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.unit.dp

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BrowserScreen(
    viewModel: BrowserViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    var showTabSidebar by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showHistory by remember { mutableStateOf(false) }
    var showMediaGrabber by remember { mutableStateOf(false) }

    val historyItems by viewModel.historyItems.collectAsStateWithLifecycle()
    val historySearchQuery by viewModel.historySearchQuery.collectAsStateWithLifecycle()

    val customView = viewModel.tabManager.customView

    // Back: close overlays first (custom video view → media grabber → history → settings → sidebar), then navigate back or exit
    BackHandler(enabled = true) {
        when {
            customView != null -> viewModel.tabManager.hideCustomView()
            showMediaGrabber -> showMediaGrabber = false
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

    val popupWarning = viewModel.popupWarningMessage
    LaunchedEffect(popupWarning) {
        if (!popupWarning.isNullOrBlank()) {
            Toast.makeText(context, popupWarning, Toast.LENGTH_SHORT).show()
            viewModel.clearPopupWarning()
        }
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
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = { focusManager.clearFocus() },
            )
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
                        onTouch = { focusManager.clearFocus() },
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
                        onTapBackground = { focusManager.clearFocus() },
                    )
                }
            }

            // Top-of-bar linear page loading progress indicator
            val activeTab = viewModel.activeTab
            val isLoading = activeTab?.isLoading == true
            val rawProgress = activeTab?.progress ?: 0
            val targetProgress = if (isLoading) (rawProgress.coerceAtLeast(10) / 100f) else 1f
            val animatedProgress by animateFloatAsState(
                targetValue = targetProgress,
                animationSpec = tween(durationMillis = 200, easing = LinearOutSlowInEasing),
                label = "PageLoadingProgress",
            )

            if (isLoading && animatedProgress < 1f) {
                LinearProgressIndicator(
                    progress = { animatedProgress },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(2.5.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = Color.Transparent,
                )
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
                onPlayerClick = { (context as? Activity)?.finish() },
                mediaCount = viewModel.activeTabMediaCount,
                onMediaGrabberClick = { showMediaGrabber = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget),
            )
        }

        // 1DM+ Media Grabber Bottom Sheet modal
        if (showMediaGrabber) {
            MediaGrabberBottomSheet(
                mediaItems = viewModel.activeTabMediaItems,
                onDismissRequest = { showMediaGrabber = false },
                onClearAll = { viewModel.clearActiveTabMedia() },
                onQualitySelected = { itemId, qualityUrl ->
                    viewModel.updateMediaQuality(itemId, qualityUrl)
                }
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
            isAdBlockUpdating = viewModel.isAdBlockUpdating,
            adBlockStatusMessage = viewModel.adBlockStatusMessage,
            onUpdateAdBlockFilters = { viewModel.refreshAdBlockFilters() },
            denyAllCrossDomainPopupsThisSession = viewModel.denyAllCrossDomainPopupsThisSession,
            onToggleDenyAllThisSession = { viewModel.setDenyAllPopupsThisSession(it) },
        )

        // Cross-domain pop-up permission bottom sheet modal
        PopupPermissionBottomSheet(
            request = viewModel.pendingPopupRequest,
            onAllow = { viewModel.allowPendingPopup() },
            onDeny = { viewModel.denyPendingPopup() },
            onDenyAllThisSession = { viewModel.denyPendingPopupAndBlockSession() },
        )

        // Fullscreen custom video view overlay
        if (customView != null) {
            AndroidView(
                factory = {
                    (customView.parent as? android.view.ViewGroup)?.removeView(customView)
                    customView
                },
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black)
            )
        }
    }
}


@Composable
private fun BrowserWebView(
    viewModel: BrowserViewModel,
    tabId: String,
    onTouch: () -> Unit = {},
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
                webView.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        onTouch()
                    }
                    false
                }
                webView
            },
            update = { wv ->
                wv.setOnTouchListener { _, event ->
                    if (event.action == MotionEvent.ACTION_DOWN) {
                        onTouch()
                    }
                    false
                }
                if (targetUrl.isNotBlank() && targetUrl != "about:blank" && wv.url != targetUrl) {
                    wv.loadUrl(targetUrl)
                }
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

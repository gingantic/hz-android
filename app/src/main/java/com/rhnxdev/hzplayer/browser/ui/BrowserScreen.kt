package com.rhnxdev.hzplayer.browser.ui

import android.app.Activity
import android.widget.Toast
import android.webkit.WebView
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.core.tween
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
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
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.collectAsState
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import com.rhnxdev.hzplayer.browser.BrowserViewModel

import android.view.MotionEvent
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.animateFloatAsState
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

    // Track tab switch direction for slide animation
    var tabSlideDirection by remember { mutableIntStateOf(0) } // -1 = prev (slide right), 1 = next (slide left)
    val swipeThresholdPx = with(LocalDensity.current) { 48.dp.toPx() }

    val onSwipeNext: () -> Unit = {
        if (viewModel.tabCount > 1) {
            tabSlideDirection = 1
            viewModel.switchToNextTab()
        }
    }
    val onSwipePrev: () -> Unit = {
        if (viewModel.tabCount > 1) {
            tabSlideDirection = -1
            viewModel.switchToPreviousTab()
        }
    }

    // Swipe-to-switch-tab modifier for toolbars (Chrome-style)
    val toolbarSwipeModifier = Modifier.pointerInput(Unit) {
        var totalX = 0f
        var triggered = false
        detectDragGestures(
            onDragStart = { totalX = 0f; triggered = false },
            onDrag = { change, dragAmount ->
                totalX += dragAmount.x
                if (!triggered && kotlin.math.abs(totalX) > swipeThresholdPx) {
                    triggered = true
                    change.consume()
                    if (totalX < 0) onSwipeNext() else onSwipePrev()
                }
            },
        )
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
        // Main content — Top Bar + WebView + Bottom Bar
        Column(modifier = Modifier.fillMaxSize()) {
            val activeId = viewModel.activeTabId
            val activeTab = viewModel.tabs.find { it.id == activeId }
            val showNewTab = activeId == null || activeTab == null ||
                    activeTab.url.isBlank() || activeTab.url == "about:blank"

            // Top URL Address Bar — always intact and visible across all tabs
            BrowserTopBar(
                url = viewModel.urlInput,
                currentTabUrl = viewModel.activeTab?.url ?: "",
                isLoading = viewModel.activeTab?.isLoading == true,
                progress = viewModel.activeTab?.progress ?: 0,
                onUrlChange = { viewModel.urlInput = it },
                onUrlSubmit = { viewModel.navigate(viewModel.urlInput) },
                onReload = { viewModel.reload() },
                onStopLoading = { viewModel.stopLoading() },
                modifier = Modifier
                    .fillMaxWidth()
                    .then(toolbarSwipeModifier),
            )

            // Isolated content container (WebView + NewTabPage)
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .background(MaterialTheme.colorScheme.background),
            ) {
                // WebView content with slide animation on tab switch
                AnimatedContent(
                    targetState = activeId,
                    transitionSpec = {
                        val direction = tabSlideDirection
                        (slideInHorizontally(
                            animationSpec = tween(250),
                            initialOffsetX = { fullWidth -> fullWidth * direction },
                        ) togetherWith slideOutHorizontally(
                            animationSpec = tween(250),
                            targetOffsetX = { fullWidth -> -fullWidth * direction },
                        ))
                    },
                    label = "TabSwitchAnimation",
                    modifier = Modifier.fillMaxSize(),
                ) { targetTabId ->
                    Box(modifier = Modifier.fillMaxSize()) {
                        if (targetTabId != null) {
                            BrowserWebView(
                                viewModel = viewModel,
                                tabId = targetTabId,
                                onTouch = { focusManager.clearFocus() },
                            )
                        }

                        val isTargetNewTab = targetTabId == null ||
                                viewModel.tabs.find { it.id == targetTabId }?.let {
                                    it.url.isBlank() || it.url == "about:blank"
                                } == true

                        if (isTargetNewTab) {
                            NewTabPage(
                                onUrlEntered = { url ->
                                    if (url.isNotBlank()) {
                                        if (targetTabId != null) viewModel.navigate(url)
                                        else viewModel.createTab(url)
                                    }
                                },
                                onTapBackground = { focusManager.clearFocus() },
                            )
                        }
                    }
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
                onPlayerClick = { (context as? Activity)?.finish() },
                mediaCount = viewModel.activeTabMediaCount,
                onMediaGrabberClick = { showMediaGrabber = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .windowInsetsPadding(WindowInsets.imeAnimationTarget)
                    .then(toolbarSwipeModifier),
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
        )

        // Cross-domain pop-up permission bottom sheet modal
        PopupPermissionBottomSheet(
            request = viewModel.pendingPopupRequest,
            onAllow = { viewModel.allowPendingPopup() },
            onDeny = { viewModel.denyPendingPopup() },
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
            wv.setBackgroundColor(android.graphics.Color.TRANSPARENT)
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

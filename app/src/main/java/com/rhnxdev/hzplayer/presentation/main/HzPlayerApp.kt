package com.rhnxdev.hzplayer.presentation.main

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.core.util.isVideoExtension
import com.rhnxdev.hzplayer.presentation.main.components.MainTabPager
import com.rhnxdev.hzplayer.presentation.navigation.MainNavHost
import com.rhnxdev.hzplayer.presentation.navigation.NavRoutes
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.components.FloatingVideoPlayer
import com.rhnxdev.hzplayer.presentation.settings.components.UpdateDialog

/**
 * Top-level application composable shell managing tab navigation, full-screen overlay transitions,
 * in-app floating player, and updates.
 */
@Composable
fun HzPlayerApp(
    initialTabIndex: Int,
    permissionDenied: Boolean = false,
    onRequestPermissions: () -> Unit = {},
    onPipEligibilityChange: (Boolean) -> Unit = {},
    incomingMediaUri: String? = null,
    incomingMediaHeaders: Map<String, String>? = null,
    incomingMimeType: String? = null,
    incomingMediaTitle: String? = null,
    incomingFromBrowser: Boolean = false,
    onIncomingMediaConsumed: () -> Unit = {},
    onOpenBrowser: (String?) -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Full-screen overlay routes draw over the main tab layout
    val isFullScreen by remember(currentRoute) {
        derivedStateOf {
            currentRoute == NavRoutes.SEARCH ||
                currentRoute == NavRoutes.AUDIO_PLAYER ||
                currentRoute?.startsWith("video_player") == true ||
                currentRoute?.startsWith("album_detail") == true ||
                currentRoute?.startsWith("artist_detail") == true
        }
    }

    // Shared player ViewModel (activity-scoped — same instance everywhere)
    val playerViewModel: PlayerViewModel = hiltViewModel()
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val floatingEnabled by playerViewModel.backgroundPlay.collectAsStateWithLifecycle()

    // ViewModel for tab persistence
    val mainViewModel: MainViewModel = hiltViewModel()

    val pagerState = rememberPagerState(
        initialPage = initialTabIndex,
        pageCount = { bottomNavDestinations.size }
    )

    // Save current tab index in memory instantly (no disk I/O on tab switch)
    LaunchedEffect(pagerState.currentPage) {
        mainViewModel.setSelectedTabIndex(pagerState.currentPage)
    }

    // Persist to DataStore only when app goes to background
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_STOP) {
                mainViewModel.persistTabIndex()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    // Keep the Activity-level PiP eligibility flag in sync with the player state.
    val pipEligibleNow by remember(floatingEnabled, playerState.isVideo, playerState.currentTitle) {
        derivedStateOf {
            floatingEnabled && playerState.isVideo && playerState.currentTitle != null
        }
    }

    LaunchedEffect(pipEligibleNow) {
        onPipEligibilityChange(pipEligibleNow)
    }

    val context = LocalContext.current
    LaunchedEffect(playerState.isPlaying, pipEligibleNow) {
        val activity = context as? MainActivity
        if (pipEligibleNow && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && activity != null) {
            activity.buildPipParams(playerState.isPlaying)?.let { params ->
                activity.setPictureInPictureParams(params)
            }
        }
    }

    // Handle incoming media URI from external VIEW intents (file chooser / share / browser).
    LaunchedEffect(incomingMediaUri) {
        val uri = incomingMediaUri ?: return@LaunchedEffect
        val fileName = incomingMediaTitle ?: uri.substringAfterLast('/').substringBefore('?')
        val isVideo = incomingMimeType?.let { mime ->
            mime.startsWith("video/") ||
                mime.equals("application/x-mpegURL", ignoreCase = true) ||
                mime.equals("application/vnd.apple.mpegurl", ignoreCase = true) ||
                mime.equals("application/dash+xml", ignoreCase = true)
        } == true ||
            isVideoExtension(fileName) ||
            uri.contains("video") // fallback for content:// URIs without clear extension
        playerViewModel.playUri(uri, fileName, isVideo = isVideo, headers = incomingMediaHeaders ?: emptyMap())
        if (isVideo) {
            playerViewModel.onVideoStarted()
            navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
        } else {
            navController.navigate(NavRoutes.AUDIO_PLAYER)
        }
        onIncomingMediaConsumed()
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Layer 1: Main swipeable tabs + bottom nav (always composed to support smooth transitions and state retention)
        MainTabPager(
            pagerState = pagerState,
            isFullScreen = isFullScreen,
            navController = navController,
            playerViewModel = playerViewModel,
            onRequestPermissions = onRequestPermissions,
            onOpenBrowser = onOpenBrowser,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 2: Full-screen NavHost overlay (always composed — preserves state)
        MainNavHost(
            navController = navController,
            playerViewModel = playerViewModel,
            incomingFromBrowser = incomingFromBrowser,
            modifier = Modifier.fillMaxSize(),
        )

        // Layer 3: In-app floating video player (YouTube-style mini window).
        FloatingVideoPlayer(
            viewModel = playerViewModel,
            uiState = playerState,
            visible = floatingEnabled && playerState.isVideo &&
                playerState.currentTitle != null && !isFullScreen,
            onExpand = {
                playerViewModel.onVideoStarted()
                navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
            },
            onClose = { playerViewModel.stop() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 84.dp),
        )

        // Startup update reminder dialog
        val pendingUpdate by mainViewModel.pendingUpdate.collectAsStateWithLifecycle()
        pendingUpdate?.let { info ->
            UpdateDialog(
                updateInfo = info,
                onDismiss = { mainViewModel.dismissUpdate() },
                onDontShowAgain = { mainViewModel.dismissUpdateForever() },
            )
        }
    }
}

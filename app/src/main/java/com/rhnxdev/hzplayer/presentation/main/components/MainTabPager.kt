package com.rhnxdev.hzplayer.presentation.main.components

import android.content.res.Configuration
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.NavigationDrawerItemDefaults
import androidx.compose.material3.NavigationRailItemDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.rhnxdev.hzplayer.core.components.HideOnScrollState
import com.rhnxdev.hzplayer.core.components.LocalHideOnScrollState
import com.rhnxdev.hzplayer.core.components.rememberHideOnScrollState
import com.rhnxdev.hzplayer.core.designsystem.LocalMiniPlayerInset
import com.rhnxdev.hzplayer.core.designsystem.stableContentStartPadding
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarHorizontalPadding
import com.rhnxdev.hzplayer.core.util.isAudioMedia
import com.rhnxdev.hzplayer.core.util.isVideoMedia
import com.rhnxdev.hzplayer.presentation.audio.AudioBrowserScreen
import com.rhnxdev.hzplayer.presentation.browse.FileBrowserScreen
import com.rhnxdev.hzplayer.presentation.navigation.NavRoutes
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations
import com.rhnxdev.hzplayer.presentation.network.NetworkScreen
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.settings.SettingsScreen
import com.rhnxdev.hzplayer.presentation.video.VideoLibraryScreen
import kotlinx.coroutines.launch

/**
 * Main navigation and horizontal page layout for the primary app tabs.
 *
 * Portrait uses [HideableNavigationBar]; landscape uses a navigation rail. The top bars and the
 * portrait bottom bar share one [HideOnScrollState], so they leave and return together.
 */
@Composable
fun MainTabPager(
    pagerState: PagerState,
    isFullScreen: Boolean,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onRequestPermissions: () -> Unit,
    onOpenBrowser: (String?) -> Unit,
    modifier: Modifier = Modifier,
) {
    val scope = rememberCoroutineScope()
    val colorScheme = MaterialTheme.colorScheme

    val suiteItemColors = NavigationSuiteDefaults.itemColors(
        navigationBarItemColors = NavigationBarItemDefaults.colors(
            indicatorColor = colorScheme.primaryContainer,
            selectedIconColor = colorScheme.onPrimaryContainer,
            selectedTextColor = colorScheme.primary,
            unselectedIconColor = colorScheme.onSurfaceVariant,
            unselectedTextColor = colorScheme.onSurfaceVariant,
        ),
        navigationRailItemColors = NavigationRailItemDefaults.colors(
            indicatorColor = colorScheme.primaryContainer,
            selectedIconColor = colorScheme.onPrimaryContainer,
            selectedTextColor = colorScheme.primary,
            unselectedIconColor = colorScheme.onSurfaceVariant,
            unselectedTextColor = colorScheme.onSurfaceVariant,
        ),
        navigationDrawerItemColors = NavigationDrawerItemDefaults.colors(
            selectedContainerColor = colorScheme.primaryContainer,
            selectedIconColor = colorScheme.onPrimaryContainer,
            selectedTextColor = colorScheme.primary,
            unselectedIconColor = colorScheme.onSurfaceVariant,
            unselectedTextColor = colorScheme.onSurfaceVariant,
        )
    )

    // Landscape: use a navigation rail so the bottom edge is free for content.
    // Portrait: the scaffold draws no bar; HideableNavigationBar below it can slide away.
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val navLayoutType = if (isLandscape) NavigationSuiteType.NavigationRail
    else NavigationSuiteType.None

    val hideState = rememberHideOnScrollState()
    // A tab switch always lands on visible bars, even if the previous tab left them hidden.
    LaunchedEffect(pagerState.currentPage) { hideState.show() }

    val selectTab: (Int) -> Unit = { index ->
        // Instant tab switch — no animation for snappy feel on weak SOCs
        scope.launch { pagerState.scrollToPage(index) }
    }

    // Force the rail to the RIGHT by flipping layout direction for the scaffold only;
    // content re-flips back to the real direction so it reads normally.
    val realDirection = LocalLayoutDirection.current
    val scaffoldDirection = if (isLandscape) LayoutDirection.Rtl else realDirection

    CompositionLocalProvider(LocalLayoutDirection provides scaffoldDirection) {
        Column(modifier = modifier) {
            NavigationSuiteScaffold(
                layoutType = navLayoutType,
                navigationSuiteItems = {
                    bottomNavDestinations.forEachIndexed { index, dest ->
                        item(
                            icon = {
                                Icon(
                                    dest.icon,
                                    contentDescription = stringResource(dest.labelRes),
                                )
                            },
                            label = { Text(stringResource(dest.labelRes)) },
                            selected = pagerState.currentPage == index,
                            onClick = { selectTab(index) },
                            colors = suiteItemColors
                        )
                    }
                },
                containerColor = MaterialTheme.colorScheme.background,
                contentColor = MaterialTheme.colorScheme.onBackground,
                navigationSuiteColors = NavigationSuiteDefaults.colors(
                    navigationBarContainerColor = MaterialTheme.colorScheme.surface,
                    navigationRailContainerColor = MaterialTheme.colorScheme.surface,
                    navigationDrawerContainerColor = MaterialTheme.colorScheme.surface,
                ),
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth(),
            ) {
                CompositionLocalProvider(LocalLayoutDirection provides realDirection) {
                    MainTabContent(
                        pagerState = pagerState,
                        isFullScreen = isFullScreen,
                        isLandscape = isLandscape,
                        hideState = hideState,
                        navController = navController,
                        playerViewModel = playerViewModel,
                        onRequestPermissions = onRequestPermissions,
                        onOpenBrowser = onOpenBrowser,
                    )
                }
            }

            if (!isLandscape) {
                HideableNavigationBar(
                    state = hideState,
                    selectedIndex = pagerState.currentPage,
                    onSelect = selectTab,
                    itemColors = suiteItemColors.navigationBarItemColors,
                )
            }
        }
    }
}

/** Pager with the five tab screens and the mini player, hosting the scroll-hide connection. */
@Composable
private fun MainTabContent(
    pagerState: PagerState,
    isFullScreen: Boolean,
    isLandscape: Boolean,
    hideState: HideOnScrollState,
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    onRequestPermissions: () -> Unit,
    onOpenBrowser: (String?) -> Unit,
) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // The mini player floats over the content, so publish its height for the
        // FABs inside the pager to offset themselves by.
        var miniPlayerHeightPx by remember { mutableIntStateOf(0) }
        val density = LocalDensity.current
        val miniPlayerInset by animateDpAsState(
            targetValue = with(density) { miniPlayerHeightPx.toDp() },
            animationSpec = tween(durationMillis = 200),
            label = "miniPlayerInset",
        )

        CompositionLocalProvider(LocalMiniPlayerInset provides miniPlayerInset) {
            // Main content area. Lists inside the pager report their scrolling to the connection.
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(hideState.nestedScrollConnection)
            ) {
                val originalViewConfig = LocalViewConfiguration.current
                val customViewConfig = remember(originalViewConfig, density) {
                    object : ViewConfiguration by originalViewConfig {
                        override val touchSlop: Float
                            get() = with(density) { 2.dp.toPx() }
                    }
                }

                CompositionLocalProvider(
                    LocalViewConfiguration provides customViewConfig,
                    LocalHideOnScrollState provides hideState,
                ) {
                    HorizontalPager(
                        state = pagerState,
                        beyondViewportPageCount = 0,
                        userScrollEnabled = false,
                        modifier = Modifier
                            .fillMaxSize()
                            .statusBarsPadding()
                            .padding(
                                if (isLandscape) stableContentStartPadding()
                                else stableNavBarHorizontalPadding()
                            ),
                    ) { page ->
                        when (page) {
                            0 -> VideoLibraryScreen(
                                isActive = pagerState.currentPage == page,
                                onVideoClicked = { video ->
                                    playerViewModel.playUri(video.uri, video.title, isVideo = true)
                                    navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                },
                                onPlayVideoPlaylist = { playlist, startIndex ->
                                    playerViewModel.playVideoPlaylist(playlist, startIndex)
                                    navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                },
                                onPlayAsAudio = { video ->
                                    playerViewModel.playUri(video.uri, video.title, isVideo = false)
                                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                                },
                                onOpenHistory = { navController.navigate(NavRoutes.HISTORY) },
                            )
                            1 -> AudioBrowserScreen(
                                isActive = pagerState.currentPage == page,
                                onSongClicked = { audio, playlist ->
                                    playerViewModel.playAudioPlaylist(playlist, playlist.indexOf(audio))
                                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                                },
                                onAlbumClicked = { navController.navigate(NavRoutes.albumDetail(it.title)) },
                                onArtistClicked = { navController.navigate(NavRoutes.artistDetail(it.name)) },
                            )
                            2 -> FileBrowserScreen(
                                fullScreenOverlay = isFullScreen,
                                isActive = pagerState.currentPage == page,
                                onFileClicked = { file ->
                                    val isVideoFile = isVideoMedia(file.name, file.mimeType)
                                    playerViewModel.playUri(file.path, file.name, isVideo = isVideoFile)
                                    when {
                                        isVideoFile -> {
                                            navController.navigate(
                                                "video_player/${file.id}"
                                            )
                                        }
                                        isAudioMedia(file.name, file.mimeType) -> {
                                            navController.navigate(NavRoutes.AUDIO_PLAYER)
                                        }
                                    }
                                },
                                onPlayAllVideos = { playlist ->
                                    playerViewModel.playVideoPlaylist(playlist)
                                    navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                },
                                onPlayVideoPlaylist = { playlist, startIndex ->
                                    playerViewModel.playVideoPlaylist(playlist, startIndex)
                                    navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                },
                                onPlayAsAudio = { file ->
                                    playerViewModel.playUri(file.path, file.name, isVideo = false)
                                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                                },
                            )
                            3 -> NetworkScreen(
                                fullScreenOverlay = isFullScreen,
                                isActive = pagerState.currentPage == page,
                                onPlayStream = { url, title, isVideo, mimeType, headers ->
                                    playerViewModel.playNetworkUri(url, title, isVideo, mimeType, headers)
                                    if (isVideo) {
                                        navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                    } else {
                                        navController.navigate(NavRoutes.AUDIO_PLAYER)
                                    }
                                },
                                onPlayRemoteFile = { uri, title, isVideo, mimeType ->
                                    playerViewModel.playNetworkUri(uri, title, isVideo, mimeType)
                                    if (isVideo) {
                                        navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                    } else {
                                        navController.navigate(NavRoutes.AUDIO_PLAYER)
                                    }
                                },
                                onPlayAllVideos = { playlist ->
                                    playerViewModel.playVideoPlaylist(playlist)
                                    navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                },
                                onOpenBrowser = { url ->
                                    onOpenBrowser(url)
                                },
                            )
                            4 -> SettingsScreen(
                                onRequestPermissions = onRequestPermissions,
                            )
                        }
                    }
                }
            }

            // Mini player above bottom nav (scoped to component — position updates don't recompose full tree)
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .onSizeChanged { miniPlayerHeightPx = it.height }
            ) {
                MiniPlayerSection(
                    playerViewModel = playerViewModel,
                    isFullScreen = isFullScreen,
                    onNavigateToPlayer = { navController.navigate(NavRoutes.AUDIO_PLAYER) },
                    onNavigateToVideoPlayer = { navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID) },
                )
            }
        }
    }
}

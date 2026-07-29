package com.rhnxdev.hzplayer.presentation.main.components

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.navigation.NavHostController
import com.rhnxdev.hzplayer.core.designsystem.stableContentStartPadding
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarHorizontalPadding
import com.rhnxdev.hzplayer.core.util.isAudioExtension
import com.rhnxdev.hzplayer.core.util.isVideoExtension
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
 * Main bottom navigation scaffold and horizontal page layout for the primary app tabs.
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
    val isLandscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val navLayoutType = if (isLandscape) NavigationSuiteType.NavigationRail
    else NavigationSuiteType.NavigationBar

    // Force the rail to the RIGHT by flipping layout direction for the scaffold only;
    // content re-flips back to the real direction so it reads normally.
    val realDirection = LocalLayoutDirection.current
    val scaffoldDirection = if (isLandscape) LayoutDirection.Rtl else realDirection

    CompositionLocalProvider(LocalLayoutDirection provides scaffoldDirection) {
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
                        onClick = {
                            // Instant tab switch — no animation for snappy feel on weak SOCs
                            scope.launch {
                                pagerState.scrollToPage(index)
                            }
                        },
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
            modifier = modifier,
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides realDirection) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(MaterialTheme.colorScheme.background)
                        .then(if (isLandscape) Modifier else Modifier.navigationBarsPadding())
                ) {
                    // Main content area
                    Box(modifier = Modifier.fillMaxSize()) {
                        val originalViewConfig = LocalViewConfiguration.current
                        val density = LocalDensity.current
                        val customViewConfig = remember(originalViewConfig, density) {
                            object : ViewConfiguration by originalViewConfig {
                                override val touchSlop: Float
                                    get() = with(density) { 2.dp.toPx() }
                            }
                        }

                        CompositionLocalProvider(LocalViewConfiguration provides customViewConfig) {
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
                                        onVideoClicked = {
                                            playerViewModel.onVideoStarted()
                                            navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
                                        },
                                        onPlayAsAudio = { video ->
                                            playerViewModel.playUri(video.uri, video.title, isVideo = false)
                                            navController.navigate(NavRoutes.AUDIO_PLAYER)
                                        },
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
                                            val isVideoFile = file.mimeType?.startsWith("video/") == true ||
                                                isVideoExtension(file.name)
                                            playerViewModel.playUri(file.path, file.name, isVideo = isVideoFile)
                                            when {
                                                isVideoFile -> {
                                                    navController.navigate(
                                                        "video_player/${file.id}"
                                                    )
                                                }
                                                file.mimeType?.startsWith("audio/") == true ||
                                                    isAudioExtension(file.name) -> {
                                                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                                                }
                                            }
                                        },
                                        onPlayAllVideos = { playlist ->
                                            playerViewModel.playVideoPlaylist(playlist)
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
    }
}

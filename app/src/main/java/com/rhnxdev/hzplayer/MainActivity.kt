package com.rhnxdev.hzplayer

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.core.content.ContextCompat
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhnxdev.hzplayer.presentation.audio.AudioBrowserScreen
import com.rhnxdev.hzplayer.presentation.browse.FileBrowserScreen
import com.rhnxdev.hzplayer.presentation.navigation.AppDestination
import com.rhnxdev.hzplayer.presentation.navigation.NavRoutes
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations
import com.rhnxdev.hzplayer.presentation.player.AudioPlayerScreen
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.components.MiniPlayerBar
import com.rhnxdev.hzplayer.presentation.player.VideoPlayerScreen
import com.rhnxdev.hzplayer.presentation.search.SearchScreen
import com.rhnxdev.hzplayer.presentation.settings.SettingsScreen
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import com.rhnxdev.hzplayer.presentation.video.VideoLibraryScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { _ -> }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestMediaPermissions()
        window.setBackgroundDrawableResource(android.R.color.black)
        setContent {
            HzPlayerTheme {
                HzPlayerApp()
            }
        }
    }

    private fun requestMediaPermissions() {
        val permissions = mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_AUDIO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_AUDIO)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_VIDEO)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_VIDEO)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        }
    }
}

@Composable
fun HzPlayerApp() {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Full-screen overlay routes draw over the main tab layout
    val isFullScreen = currentRoute == NavRoutes.SEARCH ||
        currentRoute == NavRoutes.AUDIO_PLAYER ||
        currentRoute?.startsWith("video_player") == true

    val pagerState = rememberPagerState(pageCount = { bottomNavDestinations.size })
    val scope = rememberCoroutineScope()

    // Shared player ViewModel (activity-scoped — same instance everywhere)
    val playerViewModel: PlayerViewModel = hiltViewModel()

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // Layer 1: Main swipeable tabs + bottom nav (always composed to support smooth transitions and state retention)
        NavigationSuiteScaffold(
            navigationSuiteItems = {
                bottomNavDestinations.forEachIndexed { index, dest ->
                    item(
                        icon = {
                            Icon(
                                dest.icon,
                                contentDescription = dest.label,
                            )
                        },
                        label = { Text(dest.label) },
                        selected = pagerState.currentPage == index,
                        onClick = {
                            scope.launch {
                                pagerState.animateScrollToPage(index)
                            }
                        },
                    )
                }
            },
        ) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                // Main content area
                Box(modifier = Modifier.weight(1f)) {
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
                            beyondViewportPageCount = 1,
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding(),
                        ) { page ->
                        when (page) {
                            0 -> VideoLibraryScreen(
                                onVideoClicked = { videoId ->
                                    navController.navigate("video_player/$videoId")
                                },
                            )
                            1 -> AudioBrowserScreen(
                                onSongClicked = { audio ->
                                    playerViewModel.playAudio(audio)
                                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                                },
                            )
                            2 -> FileBrowserScreen()
                            3 -> SettingsScreen()
                        }
                    }
                }
            }

                // Mini player above bottom nav
                val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
                val progress = if (playerState.duration > 0) {
                    (playerState.currentPosition.toFloat() / playerState.duration.toFloat())
                        .coerceIn(0f, 1f)
                } else 0f
                MiniPlayerBar(
                    title = playerState.currentTitle ?: "",
                    subtitle = playerState.currentArtist ?: "",
                    isPlaying = playerState.isPlaying,
                    progress = progress,
                    onPlayPause = { playerViewModel.onPlayPause() },
                    onNext = { playerViewModel.onSkipForward() },
                    onClick = { navController.navigate(NavRoutes.AUDIO_PLAYER) },
                    onDismiss = { playerViewModel.stop() },
                    visible = playerState.currentTitle != null,
                )
            }
        }

        // Layer 2: Full-screen NavHost overlay (always composed — preserves state)
        NavHost(
            navController = navController,
            startDestination = "__main_tabs",
            modifier = Modifier
                .fillMaxSize()
                .then(
                    if (isFullScreen) {
                        Modifier.pointerInput(Unit) {} // Block touches to underlying Layer 1
                    } else {
                        Modifier
                    }
                ),
        ) {
            // Hidden — the pager above renders the tabs
            composable("__main_tabs") { }

            composable(
                route = NavRoutes.SEARCH,
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
                popEnterTransition = { slideInHorizontally { -it } },
                popExitTransition = { slideOutHorizontally { it } },
            ) {
                SearchScreen(
                    onVideoClicked = { videoId ->
                        navController.navigate("video_player/$videoId")
                    },
                )
            }

            composable(
                route = NavRoutes.VIDEO_PLAYER,
                arguments = listOf(navArgument("videoId") { type = NavType.IntType }),
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
                popEnterTransition = { slideInHorizontally { -it } },
                popExitTransition = { slideOutHorizontally { it } },
            ) {
                VideoPlayerScreen(
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = NavRoutes.AUDIO_PLAYER,
                enterTransition = { slideInVertically(initialOffsetY = { it }) },
                exitTransition = { slideOutVertically(targetOffsetY = { it }) },
                popEnterTransition = { slideInVertically(initialOffsetY = { it }) },
                popExitTransition = { slideOutVertically(targetOffsetY = { it }) },
            ) {
                AudioPlayerScreen(
                    onBack = { navController.popBackStack() },
                )
            }
        }
    }
}

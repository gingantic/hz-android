package com.rhnxdev.hzplayer

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.background
import androidx.compose.ui.Alignment
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.statusBarsPadding
import com.rhnxdev.hzplayer.core.designsystem.stableNavBarHorizontalPadding
import com.rhnxdev.hzplayer.core.designsystem.stableContentStartPadding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalViewConfiguration
import androidx.compose.ui.platform.ViewConfiguration
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteDefaults
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteScaffold
import androidx.compose.material3.adaptive.navigationsuite.NavigationSuiteType
import android.content.res.Configuration
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.State
import androidx.compose.ui.unit.dp
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.core.content.ContextCompat
import com.rhnxdev.hzplayer.presentation.main.MainViewModel
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.rhnxdev.hzplayer.presentation.audio.AudioBrowserScreen
import com.rhnxdev.hzplayer.presentation.audio.AlbumDetailScreen
import com.rhnxdev.hzplayer.presentation.audio.ArtistDetailScreen
import com.rhnxdev.hzplayer.presentation.browse.FileBrowserScreen
import com.rhnxdev.hzplayer.presentation.navigation.AppDestination
import com.rhnxdev.hzplayer.presentation.navigation.NavRoutes
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations
import com.rhnxdev.hzplayer.presentation.network.NetworkScreen
import com.rhnxdev.hzplayer.presentation.player.AudioPlayerScreen
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.components.MiniPlayerBar
import com.rhnxdev.hzplayer.presentation.player.components.FloatingVideoPlayer
import com.rhnxdev.hzplayer.presentation.player.VideoPlayerScreen
import com.rhnxdev.hzplayer.presentation.search.SearchScreen
import com.rhnxdev.hzplayer.presentation.settings.SettingsScreen
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import com.rhnxdev.hzplayer.presentation.video.VideoLibraryScreen
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /** Tracks whether any permission was denied on the last attempt. */
    private var permissionDenied by mutableStateOf(false)

    /**
     * Whether the in-app floating window (and thus system PiP) is currently
     * eligible: toggle on + a video is playing with a known title. Updated from
     * [HzPlayerApp] each recomposition so the Activity-level [onUserLeaveHint]
     * can read it without its own collector.
     */
    private var pipEligible = false

    private val requestPermissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions(),
    ) { grantResults ->
        val allGranted = grantResults.values.all { it }
        permissionDenied = !allGranted
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Log.i(TAG, "onCreate")
        enableEdgeToEdge()
        requestMediaPermissions()
        window.setBackgroundDrawableResource(android.R.color.black)
        setContent {
            val mainViewModel: MainViewModel = hiltViewModel()
            val mainUiState by mainViewModel.uiState.collectAsStateWithLifecycle()
            val themeMode by mainViewModel.themeMode.collectAsStateWithLifecycle()
            val appColorArgb by mainViewModel.appColorArgb.collectAsStateWithLifecycle()
            val useDynamicColors by mainViewModel.useDynamicColors.collectAsStateWithLifecycle()

            HzPlayerTheme(
                themeMode = themeMode,
                appColorArgb = appColorArgb,
                dynamicColor = useDynamicColors
            ) {
                if (mainUiState.isReady) {
                    HzPlayerApp(
                        initialTabIndex = mainUiState.selectedTabIndex,
                        permissionDenied = permissionDenied,
                        onRequestPermissions = { requestMediaPermissions() },
                        onPipEligibilityChange = { pipEligible = it },
                    )
                } else {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(MaterialTheme.colorScheme.background)
                    )
                }
            }
        }
    }

    /**
     * Fires when the user backgrounds the app (Home / recents / app-switch) —
     * NOT on Back. If a floating-eligible video is playing, enter system PiP.
     */
    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        if (pipEligible && !isInPictureInPictureMode && Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val params = android.app.PictureInPictureParams.Builder()
                .setAspectRatio(android.util.Rational(16, 9))
                .build()
            enterPictureInPictureMode(params)
        }
    }

    /**
     * PiP mode change. Returning to the app keeps playing (the in-app floating
     * window resumes from the same engine state); the MediaSession notification
     * remains the stop control. No distinct "X closed" callback exists on stock
     * Android, so we don't force-stop here.
     */
    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: android.content.res.Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
    }

    fun requestMediaPermissions() {
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
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_MEDIA_IMAGES)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_MEDIA_IMAGES)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.READ_EXTERNAL_STORAGE)
                != PackageManager.PERMISSION_GRANTED
            ) {
                permissions.add(Manifest.permission.READ_EXTERNAL_STORAGE)
            }
        }

        if (permissions.isNotEmpty()) {
            Log.i(TAG, "requestMediaPermissions: requesting $permissions")
            requestPermissionsLauncher.launch(permissions.toTypedArray())
        } else {
            Log.d(TAG, "requestMediaPermissions: all permissions already granted")
        }
    }

    companion object {
        private const val TAG = "MainActivity"
        /**
         * Check whether the app has [android.Manifest.permission.MANAGE_EXTERNAL_STORAGE]
         * (i.e. full file-system access).
         *
         * This is **not** the same as the granular READ_MEDIA_* permissions.
         * It is required for the file browser to list arbitrary directories
         * on Android 11+.
         */
        fun isFullStorageGranted(): Boolean =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.R ||
                android.os.Environment.isExternalStorageManager()

        /** True when the app holds the granular media-read permissions it requests. */
        fun isMediaPermissionGranted(context: android.content.Context): Boolean {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_AUDIO) == PackageManager.PERMISSION_GRANTED &&
                    ContextCompat.checkSelfPermission(context, Manifest.permission.READ_MEDIA_VIDEO) == PackageManager.PERMISSION_GRANTED
            }
            return ContextCompat.checkSelfPermission(context, Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED
        }

        /**
         * Open the system page where the user can toggle "Allow access to
         * manage all files" (MANAGE_EXTERNAL_STORAGE).
         *
         * - On API 30+ → [Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION]
         *   (goes straight to the toggle for this app).
         * - On older API → generic App Info page.
         */
        fun openFullStorageSettings(context: android.content.Context) {
            val intent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                android.content.Intent(
                    android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION
                ).apply {
                    data = android.net.Uri.parse("package:${context.packageName}")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            } else {
                android.content.Intent(
                    android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
                ).apply {
                    data = android.net.Uri.fromParts("package", context.packageName, null)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
        }

        fun openAppSettings(context: android.content.Context) {
            val intent = android.content.Intent(
                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS
            ).apply {
                data = android.net.Uri.fromParts("package", context.packageName, null)
                addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        }
    }
}

@Composable
fun HzPlayerApp(
    initialTabIndex: Int,
    permissionDenied: Boolean = false,
    onRequestPermissions: () -> Unit = {},
    onPipEligibilityChange: (Boolean) -> Unit = {},
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = navBackStackEntry?.destination?.route

    // Full-screen overlay routes draw over the main tab layout
    val isFullScreen = currentRoute == NavRoutes.SEARCH ||
        currentRoute == NavRoutes.AUDIO_PLAYER ||
        currentRoute?.startsWith("video_player") == true ||
        currentRoute?.startsWith("album_detail") == true ||
        currentRoute?.startsWith("artist_detail") == true

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
    val scope = rememberCoroutineScope()



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
    val pipEligibleNow = floatingEnabled && playerState.isVideo &&
        playerState.currentTitle != null
    LaunchedEffect(pipEligibleNow) {
        onPipEligibilityChange(pipEligibleNow)
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        val suiteItemColors = NavigationSuiteDefaults.itemColors(
            navigationBarItemColors = androidx.compose.material3.NavigationBarItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            navigationRailItemColors = androidx.compose.material3.NavigationRailItemDefaults.colors(
                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
            navigationDrawerItemColors = androidx.compose.material3.NavigationDrawerItemDefaults.colors(
                selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
                selectedIconColor = MaterialTheme.colorScheme.onPrimaryContainer,
                selectedTextColor = MaterialTheme.colorScheme.primary,
                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant,
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

        // Layer 1: Main swipeable tabs + bottom nav (always composed to support smooth transitions and state retention)
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
        ) {
            CompositionLocalProvider(LocalLayoutDirection provides realDirection) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
                    // Portrait: dodge the bottom nav bar. Landscape: the rail owns the end
                    // edge and the pager dodges the start edge, so a full inset here would
                    // double-pad the rail side (dead gap).
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
                            beyondViewportPageCount = 1,
                            userScrollEnabled = false,
                            modifier = Modifier
                                .fillMaxSize()
                                .statusBarsPadding()
                                // Landscape: rail is on the end edge (owns its own inset), so only
                                // dodge the notch/nav-bar on the START edge — avoids a dead gap
                                // between content and the rail. Portrait: full horizontal inset.
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
                                        // Play media files directly from the file browser
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
                                )
                                3 -> NetworkScreen(
                                    fullScreenOverlay = isFullScreen,
                                    isActive = pagerState.currentPage == page,
                                    onPlayStream = { url, title, isVideo, mimeType ->
                                        playerViewModel.playNetworkUri(url, title, isVideo, mimeType)
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
                                )
                                4 -> SettingsScreen(
                                    onRequestPermissions = onRequestPermissions,
                                )
                            }
                        }
                    }
                }

                // Mini player above bottom nav (scoped to its own composable — position updates don't recompose full tree)
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .align(Alignment.BottomCenter)
                ) {
                    MiniPlayerSection(
                        playerViewModel = playerViewModel,
                        isFullScreen = isFullScreen,
                        onNavigateToPlayer = { navController.navigate(NavRoutes.AUDIO_PLAYER) },
                    )
                }
            }
            } // content re-flip provider
        }
        } // scaffold direction provider

        // Layer 2: Full-screen NavHost overlay (always composed — preserves state)
        NavHost(
            navController = navController,
            startDestination = "__main_tabs",
            modifier = Modifier.fillMaxSize(),
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
                    onVideoClicked = { video ->
                        playerViewModel.playVideo(video)
                        navController.navigate("video_player/${video.id}")
                    },
                    onAudioClicked = { audio, playlist ->
                        playerViewModel.playAudioPlaylist(playlist, playlist.indexOf(audio))
                        navController.navigate(NavRoutes.AUDIO_PLAYER)
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
                val context = androidx.compose.ui.platform.LocalContext.current
                VideoPlayerScreen(
                    viewModel = playerViewModel,
                    assHandler = playerViewModel.assHandler,
                    onBack = {
                        val activity = context as? android.app.Activity
                        activity?.requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                        playerViewModel.stop()
                        navController.popBackStack("__main_tabs", inclusive = false)
                    },
                    onMinimize = {
                        // Signal the full-screen surface's ON_STOP to keep playing
                        // (engine is a singleton; the mini player takes over).
                        playerViewModel.isMinimizing = true
                        navController.popBackStack("__main_tabs", inclusive = false)
                    },
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
                    viewModel = playerViewModel,
                    onBack = { navController.popBackStack() },
                )
            }

            composable(
                route = NavRoutes.ALBUM_DETAIL,
                arguments = listOf(navArgument("title") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
                popEnterTransition = { slideInHorizontally { -it } },
                popExitTransition = { slideOutHorizontally { it } },
            ) { backStackEntry ->
                val title = backStackEntry.arguments?.getString("title").orEmpty()
                AlbumDetailScreen(
                    albumTitle = title,
                    onBack = { navController.popBackStack() },
                    onSongPlayed = { navController.navigate(NavRoutes.AUDIO_PLAYER) },
                )
            }

            composable(
                route = NavRoutes.ARTIST_DETAIL,
                arguments = listOf(navArgument("name") { type = NavType.StringType }),
                enterTransition = { slideInHorizontally { it } },
                exitTransition = { slideOutHorizontally { -it } },
                popEnterTransition = { slideInHorizontally { -it } },
                popExitTransition = { slideOutHorizontally { it } },
            ) { backStackEntry ->
                val name = backStackEntry.arguments?.getString("name").orEmpty()
                ArtistDetailScreen(
                    artistName = name,
                    onBack = { navController.popBackStack() },
                    onSongPlayed = { navController.navigate(NavRoutes.AUDIO_PLAYER) },
                    onAlbumClicked = { navController.navigate(NavRoutes.albumDetail(it)) },
                )
            }
        }

        // Layer 3: In-app floating video player (YouTube-style mini window).
        // Floats above the tabs; gated so it never shows over the full-screen
        // player or the audio mini-bar.
        FloatingVideoPlayer(
            viewModel = playerViewModel,
            uiState = playerState,
            visible = floatingEnabled && playerState.isVideo &&
                playerState.currentTitle != null && !isFullScreen,
            onExpand = {
                // Floating window only shows when !isFullScreen (route already
                // popped), so just open the full player again.
                playerViewModel.onVideoStarted()
                navController.navigate(NavRoutes.VIDEO_PLAYER_NO_ID)
            },
            onClose = { playerViewModel.stop() },
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = 12.dp, bottom = 84.dp),
        )
    }
}

/**
 * Scoped mini-player composable — isolates player state collection so 250ms
 * position updates don't recompose the entire app tree.
 */
@Composable
private fun MiniPlayerSection(
    playerViewModel: PlayerViewModel,
    isFullScreen: Boolean,
    onNavigateToPlayer: () -> Unit,
) {
    val playerState by playerViewModel.uiState.collectAsStateWithLifecycle()
    val currentPosition by playerViewModel.position.collectAsStateWithLifecycle()
    val progress: State<Float> = remember {
        derivedStateOf {
            if (playerState.duration > 0) {
                (currentPosition.toFloat() / playerState.duration.toFloat()).coerceIn(0f, 1f)
            } else 0f
        }
    }
    MiniPlayerBar(
        title = playerState.currentTitle ?: "",
        subtitle = playerState.currentArtist ?: "",
        isPlaying = playerState.isPlaying,
        progress = progress,
        onPlayPause = { playerViewModel.onPlayPause() },
        onNext = { playerViewModel.onSkipNext() },
        onClick = onNavigateToPlayer,
        onDismiss = { playerViewModel.stop() },
        visible = playerState.currentTitle != null && !playerState.isVideo && !isFullScreen,
        artworkUri = playerState.currentArtworkUri,
    )
}

/** Common video file extensions for file-browser detection. */
private fun isVideoExtension(name: String): Boolean = com.rhnxdev.hzplayer.core.util.isVideoExtension(name)

/** Common audio file extensions for file-browser detection. */
private fun isAudioExtension(name: String): Boolean = com.rhnxdev.hzplayer.core.util.isAudioExtension(name)

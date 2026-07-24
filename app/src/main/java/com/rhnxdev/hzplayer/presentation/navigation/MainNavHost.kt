package com.rhnxdev.hzplayer.presentation.navigation

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.navArgument
import com.rhnxdev.hzplayer.presentation.audio.AlbumDetailScreen
import com.rhnxdev.hzplayer.presentation.audio.ArtistDetailScreen
import com.rhnxdev.hzplayer.presentation.player.AudioPlayerScreen
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.player.VideoPlayerScreen
import com.rhnxdev.hzplayer.presentation.search.SearchScreen

/**
 * Secondary full-screen NavHost overlay for search, full-screen video player,
 * full-screen audio player, and album/artist detail screens.
 */
@Composable
fun MainNavHost(
    navController: NavHostController,
    playerViewModel: PlayerViewModel,
    incomingFromBrowser: Boolean,
    modifier: Modifier = Modifier,
) {
    NavHost(
        navController = navController,
        startDestination = "__main_tabs",
        modifier = modifier.fillMaxSize(),
    ) {
        // Hidden — the pager renders the tabs
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
                onAlbumClicked = { album ->
                    navController.navigate(NavRoutes.albumDetail(album.title))
                },
                onArtistClicked = { artist ->
                    navController.navigate(NavRoutes.artistDetail(artist.name))
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
            val context = LocalContext.current
            VideoPlayerScreen(
                viewModel = playerViewModel,
                assHandler = playerViewModel.assHandler,
                onBack = {
                    val activity = context as? Activity
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    playerViewModel.isShuttingDown = true
                    playerViewModel.stop()
                    if (incomingFromBrowser) {
                        activity?.finish()
                    } else {
                        navController.popBackStack("__main_tabs", inclusive = false)
                    }
                },
                onMinimize = {
                    // Signal the full-screen surface's ON_STOP to keep playing
                    // (engine is a singleton; the mini player takes over).
                    playerViewModel.isMinimizing = true
                    val activity = context as? Activity
                    if (incomingFromBrowser) {
                        activity?.finish()
                    } else {
                        navController.popBackStack("__main_tabs", inclusive = false)
                    }
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
                onPlaySongs = { songs, index ->
                    playerViewModel.playAudioPlaylist(songs, index)
                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                },
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
                onPlaySongs = { songs, index ->
                    playerViewModel.playAudioPlaylist(songs, index)
                    navController.navigate(NavRoutes.AUDIO_PLAYER)
                },
                onAlbumClicked = { navController.navigate(NavRoutes.albumDetail(it)) },
            )
        }
    }
}

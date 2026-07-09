package com.rhnxdev.hzplayer.presentation.navigation

import androidx.annotation.StringRes
import androidx.compose.ui.graphics.vector.ImageVector
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons

sealed class AppDestination(
    val route: String,
    @StringRes val labelRes: Int,
    val icon: ImageVector,
) {
    data object VideoLibrary : AppDestination(
        route = "video_library",
        labelRes = R.string.nav_video,
        icon = HzPlayerIcons.VideoLibrary,
    )

    data object AudioBrowser : AppDestination(
        route = "audio_browser",
        labelRes = R.string.nav_audio,
        icon = HzPlayerIcons.AudioBrowser,
    )

    data object FileBrowser : AppDestination(
        route = "file_browser",
        labelRes = R.string.nav_browse,
        icon = HzPlayerIcons.FileBrowser,
    )

    data object Network : AppDestination(
        route = "network",
        labelRes = R.string.nav_network,
        icon = HzPlayerIcons.Network,
    )

    data object Settings : AppDestination(
        route = "settings",
        labelRes = R.string.nav_settings,
        icon = HzPlayerIcons.Settings,
    )
}

val bottomNavDestinations = listOf(
    AppDestination.VideoLibrary,
    AppDestination.AudioBrowser,
    AppDestination.FileBrowser,
    AppDestination.Network,
    AppDestination.Settings,
)

package com.rhnxdev.hzplayer.presentation.navigation

import androidx.compose.ui.graphics.vector.ImageVector
import com.rhnxdev.hzplayer.core.designsystem.HzPlayerIcons

sealed class AppDestination(
    val route: String,
    val label: String,
    val icon: ImageVector,
) {
    data object VideoLibrary : AppDestination(
        route = "video_library",
        label = "Video",
        icon = HzPlayerIcons.VideoLibrary,
    )

    data object AudioBrowser : AppDestination(
        route = "audio_browser",
        label = "Audio",
        icon = HzPlayerIcons.AudioBrowser,
    )

    data object FileBrowser : AppDestination(
        route = "file_browser",
        label = "Browse",
        icon = HzPlayerIcons.FileBrowser,
    )

    data object Network : AppDestination(
        route = "network",
        label = "Network",
        icon = HzPlayerIcons.Network,
    )

    data object Settings : AppDestination(
        route = "settings",
        label = "Settings",
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

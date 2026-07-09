package com.rhnxdev.hzplayer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.presentation.settings.components.ColorPickerDialog
import com.rhnxdev.hzplayer.presentation.settings.components.OpenSubtitlesApiKeyDialog
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSection
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSliderItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsToggleItem
import com.rhnxdev.hzplayer.presentation.settings.components.ThemeSelectionDialog
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onRequestPermissions: () -> Unit = {},
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var resumePlayback by remember { mutableStateOf(true) }
    var showApiKeyDialog by remember { mutableStateOf(false) }


    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val appColorArgb by settingsViewModel.appColorArgb.collectAsStateWithLifecycle()
    val dynamicColors by settingsViewModel.useDynamicColors.collectAsStateWithLifecycle()

    val currentApiKey by settingsViewModel.openSubtitlesApiKey.collectAsStateWithLifecycle()
    val seekSensitivity by settingsViewModel.seekSensitivity.collectAsStateWithLifecycle()
    val showHiddenFiles by settingsViewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val useSurfaceView by settingsViewModel.useSurfaceView.collectAsStateWithLifecycle()
    val minSongDurationSecs by settingsViewModel.minSongDurationSecs.collectAsStateWithLifecycle()
    val enableHdrPlayback by settingsViewModel.enableHdrPlayback.collectAsStateWithLifecycle()
    val debugMode by settingsViewModel.debugMode.collectAsStateWithLifecycle()

    if (showApiKeyDialog) {
        OpenSubtitlesApiKeyDialog(
            currentKey = currentApiKey,
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                settingsViewModel.saveOpenSubtitlesApiKey(key)
                showApiKeyDialog = false
            },
        )
    }

    if (showThemeDialog) {
        ThemeSelectionDialog(
            currentTheme = themeMode,
            onDismiss = { showThemeDialog = false },
            onSelect = { mode ->
                settingsViewModel.saveThemeMode(mode)
                showThemeDialog = false
            },
        )
    }

    if (showColorDialog) {
        ColorPickerDialog(
            currentColorArgb = appColorArgb,
            onDismiss = { showColorDialog = false },
            onSelect = { argb ->
                settingsViewModel.saveAppColorArgb(argb)
                showColorDialog = false
            },
        )
    }


    Column(modifier = modifier.fillMaxSize()) {
        // Toolbar
        HzPlayerTopBar(title = "Settings")

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Display
            item {
                SettingsSection(
                    title = "Display",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Theme",
                                subtitle = when (themeMode) {
                                    ThemeMode.LIGHT -> "Light"
                                    ThemeMode.DARK -> "Dark"
                                    ThemeMode.VOID -> "Void"
                                },
                                onClick = { showThemeDialog = true },
                            )
                            SettingsItem(
                                title = "Theme color",
                                subtitle = String.format("#%06X", 0xFFFFFF and appColorArgb),
                                onClick = { showColorDialog = true },
                            )
                            SettingsToggleItem(
                                title = "Dynamic colors",
                                subtitle = "Use system accent color",
                                checked = dynamicColors,
                                onCheckedChange = { settingsViewModel.saveDynamicColors(it) },
                            )
                        }
                    },
                )
            }

            // Playback Engine
            item {
                SettingsSection(
                    title = "Playback Engine",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Media engine",
                                subtitle = "ExoPlayer (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                        }
                    },
                )
            }

            // Video
            item {
                SettingsSection(
                    title = "Video",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Default subtitle track",
                                subtitle = "None (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Jump delay",
                                subtitle = "10 seconds (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Long jump delay",
                                subtitle = "20 seconds (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Resume playback",
                                subtitle = "Continue from where you left off (Not implemented)",
                                checked = resumePlayback,
                                onCheckedChange = { resumePlayback = it },
                                enabled = false,
                            )
                            SettingsToggleItem(
                                title = "Background play",
                                subtitle = "Continue audio when video is minimized (Not implemented)",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                            )
                            SettingsSliderItem(
                                title = "Gesture seek sensitivity",
                                subtitle = String.format(java.util.Locale.US, "Multiplier: %.2fx", seekSensitivity),
                                value = seekSensitivity,
                                onValueChange = { settingsViewModel.saveSeekSensitivity(it) },
                                valueRange = 0.2f..3.0f,
                            )

                            SettingsToggleItem(
                                title = "Enable HDR playback",
                                subtitle = "In progress — preference is saved but HDR/SDR mode currently ignores this toggle.",
                                checked = enableHdrPlayback,
                                onCheckedChange = { settingsViewModel.saveEnableHdrPlayback(it) },
                                enabled = false,
                            )
                        }
                    },
                )
            }

            // Subtitles
            item {
                SettingsSection(
                    title = "Subtitles",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "OpenSubtitles API Key",
                                subtitle = if (currentApiKey.isBlank()) "Not configured — get one at opensubtitles.com"
                                else "••••••••",
                                onClick = { showApiKeyDialog = true },
                            )
                        }
                    },
                )
            }

            // Audio
            item {
                SettingsSection(
                    title = "Audio",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Audio jump delay",
                                subtitle = "10 seconds (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Equalizer",
                                subtitle = "Adjust frequency bands (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Audio track info",
                                subtitle = "Show technical details (Not implemented)",
                                checked = false,
                                onCheckedChange = {},
                                enabled = false,
                            )
                            SettingsSliderItem(
                                title = "Min song duration",
                                subtitle = if (minSongDurationSecs == 0) "Disabled (show all songs)"
                                else "Hide songs under ${minSongDurationSecs}s",
                                value = minSongDurationSecs.toFloat(),
                                onValueChange = { settingsViewModel.saveMinSongDurationSecs(it.toInt()) },
                                valueRange = 0f..60f,
                                steps = 11,
                            )
                        }
                    },
                )
            }

            // Playback
            item {
                SettingsSection(
                    title = "Playback",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Default playback speed",
                                subtitle = "1.0x (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                        }
                    },
                )
            }

            // Storage & Permissions
            item {
                val context = LocalContext.current
                SettingsSection(
                    title = "Storage & Permissions",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Request media permissions",
                                subtitle = "Grant access to photos, video, and audio",
                                onClick = onRequestPermissions,
                            )
                            SettingsItem(
                                title = "Full storage access",
                                subtitle = if (MainActivity.isFullStorageGranted())
                                    "Already granted"
                                else
                                    "Allow access to all files on device",
                                onClick = { MainActivity.openFullStorageSettings(context) },
                            )
                            SettingsItem(
                                title = "Media directories",
                                subtitle = "Select folders to scan (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Show hidden files",
                                subtitle = "Display hidden files in browser",
                                checked = showHiddenFiles,
                                onCheckedChange = { settingsViewModel.setShowHiddenFiles(it) },
                            )
                        }
                    },
                )
            }

            // Advanced
            item {
                SettingsSection(
                    title = "Advanced",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Clear media cache",
                                subtitle = "Free up storage space",
                                onClick = { settingsViewModel.clearAllCache() },
                            )
                            SettingsToggleItem(
                                title = "Stats for nerds",
                                subtitle = "Show video/audio codec info in player overlay",
                                checked = debugMode,
                                onCheckedChange = { settingsViewModel.saveDebugMode(it) },
                            )
                            SettingsItem(
                                title = "About Hz Player",
                                subtitle = "Version 1.0",
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Open source licenses",
                                subtitle = "View third-party licenses (Not implemented)",
                                enabled = false,
                                onClick = {},
                            )
                        }
                    },
                )
            }

            // Bottom spacer
            item {
                Spacer(modifier = Modifier.height(Spacing.xxl))
            }
        }
    }
}

@PreviewLightDark
@Preview
@Composable
private fun SettingsScreenPreview() {
    HzPlayerTheme {
        SettingsScreen()
    }
}


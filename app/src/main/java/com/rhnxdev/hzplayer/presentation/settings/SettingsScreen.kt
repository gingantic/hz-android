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
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.player.EngineType
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
    val activeEngine by settingsViewModel.activeEngine.collectAsStateWithLifecycle()
    val availableEngines = settingsViewModel.availableEngines

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
        HzPlayerTopBar(title = stringResource(R.string.settings_title))

        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(vertical = Spacing.sm),
            verticalArrangement = Arrangement.spacedBy(Spacing.lg),
        ) {
            // Display
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_display),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_theme),
                                subtitle = when (themeMode) {
                                    ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                    ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                    ThemeMode.VOID -> stringResource(R.string.theme_void)
                                },
                                onClick = { showThemeDialog = true },
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_theme_color),
                                subtitle = String.format("#%06X", 0xFFFFFF and appColorArgb),
                                onClick = { showColorDialog = true },
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_dynamic_colors),
                                subtitle = stringResource(R.string.settings_dynamic_colors_sub),
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
                    title = stringResource(R.string.settings_playback_engine),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            // Switching engines stops current playback; the new engine
                            // takes over on the next play.
                            availableEngines.forEach { engineType ->
                                val isActive = engineType == activeEngine
                                SettingsItem(
                                    title = engineLabel(engineType),
                                    subtitle = if (isActive) stringResource(R.string.settings_engine_active)
                                    else stringResource(R.string.settings_engine_inactive),
                                    trailing = if (isActive) {
                                        { Icons.Default.Check }
                                    } else null,
                                    onClick = { settingsViewModel.selectEngine(engineType) },
                                )
                            }
                        }
                    },
                )
            }

            // Video
            item {
                SettingsSection(
                    title = stringResource(R.string.settings_video),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_default_subtitle),
                                subtitle = "None ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_jump_delay),
                                subtitle = "10 seconds ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_long_jump_delay),
                                subtitle = "20 seconds ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_resume_playback),
                                subtitle = "${stringResource(R.string.settings_resume_playback_sub)} ${stringResource(R.string.not_implemented)}",
                                checked = resumePlayback,
                                onCheckedChange = { resumePlayback = it },
                                enabled = false,
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_background_play),
                                subtitle = "${stringResource(R.string.settings_background_play_sub)} ${stringResource(R.string.not_implemented)}",
                                checked = true,
                                onCheckedChange = {},
                                enabled = false,
                            )
                            SettingsSliderItem(
                                title = stringResource(R.string.settings_gesture_sensitivity),
                                subtitle = String.format(java.util.Locale.US, "Multiplier: %.2fx", seekSensitivity),
                                value = seekSensitivity,
                                onValueChange = { settingsViewModel.saveSeekSensitivity(it) },
                                valueRange = 0.2f..3.0f,
                            )

                            SettingsToggleItem(
                                title = stringResource(R.string.settings_video_hdr),
                                subtitle = stringResource(R.string.settings_video_hdr_sub),
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
                    title = stringResource(R.string.settings_subtitles),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_opensubtitles_key),
                                subtitle = if (currentApiKey.isBlank()) stringResource(R.string.settings_opensubtitles_not_configured)
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
                    title = stringResource(R.string.settings_audio),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_audio_jump_delay),
                                subtitle = "10 seconds ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_equalizer),
                                subtitle = "${stringResource(R.string.settings_equalizer_sub)} ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_audio_track_info),
                                subtitle = "${stringResource(R.string.settings_audio_track_info_sub)} ${stringResource(R.string.not_implemented)}",
                                checked = false,
                                onCheckedChange = {},
                                enabled = false,
                            )
                            SettingsSliderItem(
                                title = stringResource(R.string.settings_min_song_duration),
                                subtitle = if (minSongDurationSecs == 0) stringResource(R.string.settings_min_song_duration_disabled)
                                else stringResource(R.string.settings_min_song_duration_hidden, minSongDurationSecs),
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
                    title = stringResource(R.string.settings_playback),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_default_speed),
                                subtitle = "1.0x ${stringResource(R.string.not_implemented)}",
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
                    title = stringResource(R.string.settings_storage_permissions),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_request_media_perms),
                                subtitle = stringResource(R.string.settings_request_media_perms_sub),
                                onClick = onRequestPermissions,
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_full_storage),
                                subtitle = if (MainActivity.isFullStorageGranted())
                                    stringResource(R.string.settings_full_storage_granted)
                                else
                                    stringResource(R.string.settings_full_storage_sub),
                                onClick = { MainActivity.openFullStorageSettings(context) },
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_media_dirs),
                                subtitle = "${stringResource(R.string.settings_media_dirs_sub)} ${stringResource(R.string.not_implemented)}",
                                enabled = false,
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_show_hidden),
                                subtitle = stringResource(R.string.settings_show_hidden_sub),
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
                    title = stringResource(R.string.settings_advanced),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_clear_cache),
                                subtitle = stringResource(R.string.settings_clear_cache_sub),
                                onClick = { settingsViewModel.clearAllCache() },
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_stats_nerds),
                                subtitle = stringResource(R.string.settings_stats_nerds_sub),
                                checked = debugMode,
                                onCheckedChange = { settingsViewModel.saveDebugMode(it) },
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_about),
                                subtitle = stringResource(R.string.settings_version),
                                onClick = {},
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_licenses),
                                subtitle = "${stringResource(R.string.settings_licenses_sub)} ${stringResource(R.string.not_implemented)}",
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

/** Human-readable label for an [EngineType] in the settings selector. */
private fun engineLabel(type: EngineType): String = when (type) {
    EngineType.EXO_PLAYER -> "ExoPlayer"
    // EngineType.VLC -> "VLC"
    // EngineType.MPV -> "mpv"
}


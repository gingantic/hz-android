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
import android.util.Log
import android.widget.Toast
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.rhnxdev.hzplayer.presentation.settings.components.UpdateDialog
import kotlinx.coroutines.launch
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Icon
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.rhnxdev.hzplayer.MainActivity
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.presentation.settings.components.ColorPickerDialog
import com.rhnxdev.hzplayer.presentation.settings.components.SubdlApiKeyDialog
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSection
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSliderItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsToggleItem
import com.rhnxdev.hzplayer.presentation.settings.components.AboutDialog
import com.rhnxdev.hzplayer.presentation.settings.components.ThemeSelectionDialog
import com.rhnxdev.hzplayer.presentation.settings.components.OrientationDialog
import com.rhnxdev.hzplayer.presentation.settings.components.ResumeModeDialog
import com.rhnxdev.hzplayer.presentation.settings.components.DecoderModeDialog
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onRequestPermissions: () -> Unit = {},
) {
    var showThemeDialog by remember { mutableStateOf(false) }
    var showColorDialog by remember { mutableStateOf(false) }
    var showApiKeyDialog by remember { mutableStateOf(false) }
    var showAboutDialog by remember { mutableStateOf(false) }
    var showOrientationDialog by remember { mutableStateOf(false) }
    var showResumeDialog by remember { mutableStateOf(false) }
    var showDecoderDialog by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    var isCheckingUpdates by remember { mutableStateOf(false) }
    var updateInfo by remember { mutableStateOf<com.rhnxdev.hzplayer.core.util.UpdateChecker.UpdateInfo?>(null) }
    var showUpdateDialog by remember { mutableStateOf(false) }


    val themeMode by settingsViewModel.themeMode.collectAsStateWithLifecycle()
    val appColorArgb by settingsViewModel.appColorArgb.collectAsStateWithLifecycle()
    val dynamicColors by settingsViewModel.useDynamicColors.collectAsStateWithLifecycle()

    val currentApiKey by settingsViewModel.subdlApiKey.collectAsStateWithLifecycle()
    val seekSensitivity by settingsViewModel.seekSensitivity.collectAsStateWithLifecycle()
    val showHiddenFiles by settingsViewModel.showHiddenFiles.collectAsStateWithLifecycle()
    val useSurfaceView by settingsViewModel.useSurfaceView.collectAsStateWithLifecycle()
    val minSongDurationSecs by settingsViewModel.minSongDurationSecs.collectAsStateWithLifecycle()
    val debugMode by settingsViewModel.debugMode.collectAsStateWithLifecycle()
    val backgroundPlay by settingsViewModel.backgroundPlay.collectAsStateWithLifecycle()
    val orientationMode by settingsViewModel.orientationMode.collectAsStateWithLifecycle()
    val resumeMode by settingsViewModel.resumeMode.collectAsStateWithLifecycle()
    val decoderMode by settingsViewModel.decoderMode.collectAsStateWithLifecycle()
    val activeEngine by settingsViewModel.activeEngine.collectAsStateWithLifecycle()
    val availableEngines = settingsViewModel.availableEngines

    if (showApiKeyDialog) {
        SubdlApiKeyDialog(
            currentKey = currentApiKey,
            onDismiss = { showApiKeyDialog = false },
            onSave = { key ->
                settingsViewModel.saveSubdlApiKey(key)
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

    if (showAboutDialog) {
        AboutDialog(onDismiss = { showAboutDialog = false })
    }

    if (showUpdateDialog && updateInfo != null) {
        UpdateDialog(
            updateInfo = updateInfo!!,
            onDismiss = { showUpdateDialog = false }
        )
    }

    if (showOrientationDialog) {
        OrientationDialog(
            currentMode = orientationMode,
            onDismiss = { showOrientationDialog = false },
            onSelect = { mode ->
                settingsViewModel.saveOrientationMode(mode)
                showOrientationDialog = false
            },
        )
    }

    if (showResumeDialog) {
        ResumeModeDialog(
            currentMode = resumeMode,
            onDismiss = { showResumeDialog = false },
            onSelect = { mode ->
                settingsViewModel.saveResumeMode(mode)
                showResumeDialog = false
            },
        )
    }

    if (showDecoderDialog) {
        DecoderModeDialog(
            currentMode = decoderMode,
            onDismiss = { showDecoderDialog = false },
            onSelect = { mode ->
                settingsViewModel.saveDecoderMode(mode)
                showDecoderDialog = false
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
                                    ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
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
                            SettingsItem(
                                title = stringResource(R.string.settings_orientation),
                                subtitle = when (orientationMode) {
                                    OrientationMode.AUTO -> stringResource(R.string.orientation_auto)
                                    OrientationMode.PORTRAIT -> stringResource(R.string.orientation_portrait)
                                    OrientationMode.LANDSCAPE -> stringResource(R.string.orientation_landscape)
                                },
                                onClick = { showOrientationDialog = true },
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
                                        { Icon(Icons.Default.Check, contentDescription = null) }
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
                                title = stringResource(R.string.settings_resume_playback),
                                subtitle = when (resumeMode) {
                                    com.rhnxdev.hzplayer.domain.model.ResumeMode.NONE -> stringResource(R.string.resume_mode_none)
                                    com.rhnxdev.hzplayer.domain.model.ResumeMode.ASK -> stringResource(R.string.resume_mode_ask)
                                    com.rhnxdev.hzplayer.domain.model.ResumeMode.ALWAYS -> stringResource(R.string.resume_mode_always)
                                },
                                onClick = { showResumeDialog = true },
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_decoder_mode),
                                subtitle = when (decoderMode) {
                                    DecoderMode.AUTO -> stringResource(R.string.decoder_mode_auto)
                                    DecoderMode.HARDWARE -> stringResource(R.string.decoder_mode_hardware)
                                    DecoderMode.SOFTWARE -> stringResource(R.string.decoder_mode_software)
                                },
                                onClick = { showDecoderDialog = true },
                            )
                            SettingsToggleItem(
                                title = stringResource(R.string.settings_background_play),
                                subtitle = stringResource(R.string.settings_background_play_sub),
                                checked = backgroundPlay,
                                onCheckedChange = { settingsViewModel.saveBackgroundPlay(it) },
                            )
                            SettingsSliderItem(
                                title = stringResource(R.string.settings_gesture_sensitivity),
                                subtitle = String.format(java.util.Locale.US, "Multiplier: %.2fx", seekSensitivity),
                                value = seekSensitivity,
                                onValueChange = { settingsViewModel.saveSeekSensitivity(it) },
                                valueRange = 0.2f..3.0f,
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
                                title = stringResource(R.string.settings_subdl_key),
                                subtitle = if (currentApiKey.isBlank()) stringResource(R.string.settings_subdl_not_configured)
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
                val mediaGranted = MainActivity.isMediaPermissionGranted(context)
                SettingsSection(
                    title = stringResource(R.string.settings_storage_permissions),
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = stringResource(R.string.settings_request_media_perms),
                                subtitle = stringResource(R.string.settings_request_media_perms_sub),
                                onClick = onRequestPermissions,
                                trailing = if (mediaGranted) {
                                    { Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50)) }
                                } else null,
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_full_storage),
                                subtitle = if (MainActivity.isFullStorageGranted())
                                    stringResource(R.string.settings_full_storage_granted)
                                else
                                    stringResource(R.string.settings_full_storage_sub),
                                onClick = { MainActivity.openFullStorageSettings(context) },
                                trailing = if (MainActivity.isFullStorageGranted()) {
                                    { Icon(Icons.Default.Check, null, tint = Color(0xFF4CAF50)) }
                                } else null,
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
                                title = stringResource(R.string.settings_check_updates),
                                subtitle = if (isCheckingUpdates) stringResource(R.string.update_checking) else stringResource(R.string.settings_check_updates_sub),
                                enabled = !isCheckingUpdates,
                                onClick = {
                                    isCheckingUpdates = true
                                    coroutineScope.launch {
                                        val result = com.rhnxdev.hzplayer.core.util.UpdateChecker.checkForUpdates()
                                        isCheckingUpdates = false
                                        when (result) {
                                            is com.rhnxdev.hzplayer.core.util.UpdateChecker.CheckResult.Available -> {
                                                updateInfo = result.info
                                                showUpdateDialog = true
                                            }
                                            is com.rhnxdev.hzplayer.core.util.UpdateChecker.CheckResult.UpToDate -> {
                                                Toast.makeText(context, R.string.update_no_updates, Toast.LENGTH_SHORT).show()
                                            }
                                            is com.rhnxdev.hzplayer.core.util.UpdateChecker.CheckResult.Error -> {
                                                Log.w("UpdateCheck", result.message)
                                            }
                                        }
                                    }
                                }
                            )
                            SettingsItem(
                                title = stringResource(R.string.settings_about),
                                subtitle = "Version ${com.rhnxdev.hzplayer.BuildConfig.VERSION_NAME}",
                                onClick = { showAboutDialog = true },
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


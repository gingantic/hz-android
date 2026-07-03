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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import com.rhnxdev.hzplayer.presentation.player.PlayerViewModel
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSection
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsToggleItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSliderItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
    playerViewModel: PlayerViewModel = hiltViewModel(),
    settingsViewModel: SettingsViewModel = hiltViewModel(),
    onRequestPermissions: () -> Unit = {},
) {
    var darkTheme by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(true) }
    var resumePlayback by remember { mutableStateOf(true) }
    var showApiKeyDialog by remember { mutableStateOf(false) }

    val currentEngine = playerViewModel.activeEngineType
    val currentApiKey by settingsViewModel.openSubtitlesApiKey.collectAsStateWithLifecycle()
    val seekSensitivity by settingsViewModel.seekSensitivity.collectAsStateWithLifecycle()
    val showHiddenFiles by settingsViewModel.showHiddenFiles.collectAsStateWithLifecycle()

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
                            SettingsToggleItem(
                                title = "Dark theme",
                                subtitle = "Use dark background",
                                checked = darkTheme,
                                onCheckedChange = { darkTheme = it },
                            )
                            SettingsToggleItem(
                                title = "Dynamic colors",
                                subtitle = "Use system accent color",
                                checked = dynamicColors,
                                onCheckedChange = { dynamicColors = it },
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
                                subtitle = when (currentEngine) {
                                    EngineType.EXO_PLAYER -> "ExoPlayer (default)"
                                    EngineType.VLC -> "libVLC"
                                },
                                onClick = {
                                    // Toggle between engines
                                    val next = when (currentEngine) {
                                        EngineType.EXO_PLAYER -> EngineType.VLC
                                        EngineType.VLC -> EngineType.EXO_PLAYER
                                    }
                                    playerViewModel.switchEngine(next)
                                },
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
                                subtitle = "None",
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Jump delay",
                                subtitle = "10 seconds",
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Long jump delay",
                                subtitle = "20 seconds",
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Resume playback",
                                subtitle = "Continue from where you left off",
                                checked = resumePlayback,
                                onCheckedChange = { resumePlayback = it },
                            )
                            SettingsToggleItem(
                                title = "Background play",
                                subtitle = "Continue audio when video is minimized",
                                checked = true,
                                onCheckedChange = {},
                            )
                            SettingsSliderItem(
                                title = "Gesture seek sensitivity",
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
                                subtitle = "10 seconds",
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Equalizer",
                                subtitle = "Adjust frequency bands",
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Audio track info",
                                subtitle = "Show technical details",
                                checked = false,
                                onCheckedChange = {},
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
                                subtitle = "1.0x",
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
                                subtitle = "Select folders to scan",
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
                                onClick = {},
                            )
                            SettingsItem(
                                title = "About Hz Player",
                                subtitle = "Version 1.0",
                                onClick = {},
                            )
                            SettingsItem(
                                title = "Open source licenses",
                                subtitle = "View third-party licenses",
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

@Composable
private fun OpenSubtitlesApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(currentKey) { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("OpenSubtitles API Key") },
        text = {
            Column {
                Text(
                    text = "Enter your API key from opensubtitles.com. Required for online subtitle search.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text("e.g. abc123xyz…") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        },
    )
}

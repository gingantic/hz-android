package com.rhnxdev.hzplayer.presentation.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import com.rhnxdev.hzplayer.core.components.HzPlayerTopBar
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSection
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsToggleItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

@Composable
fun SettingsScreen(
    modifier: Modifier = Modifier,
) {
    var darkTheme by remember { mutableStateOf(false) }
    var dynamicColors by remember { mutableStateOf(true) }
    var resumePlayback by remember { mutableStateOf(true) }
    var showHiddenFiles by remember { mutableStateOf(false) }

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

            // Storage
            item {
                SettingsSection(
                    title = "Storage",
                    content = {
                        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                            SettingsItem(
                                title = "Media directories",
                                subtitle = "Select folders to scan",
                                onClick = {},
                            )
                            SettingsToggleItem(
                                title = "Show hidden files",
                                subtitle = "Display hidden files in browser",
                                checked = showHiddenFiles,
                                onCheckedChange = { showHiddenFiles = it },
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

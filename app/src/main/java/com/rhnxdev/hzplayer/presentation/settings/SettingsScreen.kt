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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Alignment
import androidx.compose.material3.RadioButton
import com.rhnxdev.hzplayer.domain.model.ThemeMode
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSection
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsToggleItem
import com.rhnxdev.hzplayer.presentation.settings.components.SettingsSliderItem
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.ui.draw.clip
import androidx.compose.runtime.mutableFloatStateOf

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
                                subtitle = "ExoPlayer",
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
                                onClick = { settingsViewModel.clearAllCache() },
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
private fun ThemeSelectionDialog(
    currentTheme: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Select Theme") },
        text = {
            Column {
                ThemeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentTheme == mode,
                            onClick = { onSelect(mode) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when (mode) {
                                ThemeMode.LIGHT -> "Light"
                                ThemeMode.DARK -> "Dark"
                                ThemeMode.VOID -> "Void"
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorPickerDialog(
    currentColorArgb: Int,
    onDismiss: () -> Unit,
    onSelect: (Int) -> Unit,
) {
    val colorObj = remember(currentColorArgb) { Color(currentColorArgb) }
    var redValue by remember(currentColorArgb) { mutableFloatStateOf(colorObj.red * 255f) }
    var greenValue by remember(currentColorArgb) { mutableFloatStateOf(colorObj.green * 255f) }
    var blueValue by remember(currentColorArgb) { mutableFloatStateOf(colorObj.blue * 255f) }

    val presetColors = listOf(
        0xFFE85E00.toInt() to "VLC Orange",
        0xFF1E88E5.toInt() to "Blue",
        0xFF43A047.toInt() to "Green",
        0xFFD32F2F.toInt() to "Red",
        0xFF7B1FA2.toInt() to "Purple",
        0xFFC2185B.toInt() to "Pink",
        0xFF009688.toInt() to "Teal",
        0xFFFFEB3B.toInt() to "Yellow"
    )

    val selectedColorArgb = remember(redValue, greenValue, blueValue) {
        android.graphics.Color.rgb(redValue.toInt(), greenValue.toInt(), blueValue.toInt())
    }

    var hexInput by remember(currentColorArgb) {
        mutableStateOf(String.format("%06X", 0xFFFFFF and currentColorArgb))
    }

    // Sync input field when sliders are moved
    androidx.compose.runtime.LaunchedEffect(selectedColorArgb) {
        val currentHex = String.format("%06X", 0xFFFFFF and selectedColorArgb)
        if (hexInput.uppercase() != currentHex) {
            hexInput = currentHex
        }
    }

    val onHexChanged = { input: String ->
        // Keep only valid hex characters and cap at 6 digits
        val filtered = input.filter { it.isDigit() || it.uppercaseChar() in 'A'..'F' }.take(6)
        hexInput = filtered
        if (filtered.length == 6) {
            try {
                val parsedColor = android.graphics.Color.parseColor("#$filtered")
                val c = Color(parsedColor)
                redValue = c.red * 255f
                greenValue = c.green * 255f
                blueValue = c.blue * 255f
            } catch (_: IllegalArgumentException) {}
        }
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Theme Color Picker") },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                // Color Preview Box
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(80.dp)
                        .clip(RoundedCornerShape(8.dp))
                        .background(Color(selectedColorArgb)),
                    contentAlignment = Alignment.Center
                ) {
                    val contentColor = if ((redValue * 0.299f + greenValue * 0.587f + blueValue * 0.114f) > 186f) Color.Black else Color.White
                    Row(
                        modifier = Modifier
                            .border(
                                width = 1.dp,
                                color = contentColor.copy(alpha = 0.5f),
                                shape = RoundedCornerShape(4.dp)
                            )
                            .padding(horizontal = 12.dp, vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "#",
                            color = contentColor,
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.Bold
                        )
                        androidx.compose.foundation.text.BasicTextField(
                            value = hexInput,
                            onValueChange = onHexChanged,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = contentColor,
                                fontWeight = FontWeight.Bold
                            ),
                            cursorBrush = androidx.compose.ui.graphics.SolidColor(contentColor),
                            singleLine = true,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }

                // Grid of Presets
                Text(
                    text = "Presets",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )

                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presetColors.take(4).forEach { (argb, _) ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable {
                                        val c = Color(argb)
                                        redValue = c.red * 255f
                                        greenValue = c.green * 255f
                                        blueValue = c.blue * 255f
                                    }
                            )
                        }
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        presetColors.drop(4).take(4).forEach { (argb, _) ->
                            Box(
                                modifier = Modifier
                                    .size(40.dp)
                                    .clip(CircleShape)
                                    .background(Color(argb))
                                    .clickable {
                                        val c = Color(argb)
                                        redValue = c.red * 255f
                                        greenValue = c.green * 255f
                                        blueValue = c.blue * 255f
                                    }
                            )
                        }
                    }
                }

                // RGB sliders
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Custom RGB Colors",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )

                    // Red Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Red", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                            Text("${redValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Slider(
                            value = redValue,
                            onValueChange = { redValue = it },
                            valueRange = 0f..255f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                activeTrackColor = Color.Red,
                                thumbColor = Color.Red
                            )
                        )
                    }

                    // Green Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Green", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                            Text("${greenValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Slider(
                            value = greenValue,
                            onValueChange = { greenValue = it },
                            valueRange = 0f..255f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                activeTrackColor = Color.Green,
                                thumbColor = Color.Green
                            )
                        )
                    }

                    // Blue Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text("Blue", style = MaterialTheme.typography.bodyMedium, color = Color.White.copy(alpha = 0.8f))
                            Text("${blueValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = Color.White)
                        }
                        Slider(
                            value = blueValue,
                            onValueChange = { blueValue = it },
                            valueRange = 0f..255f,
                            colors = androidx.compose.material3.SliderDefaults.colors(
                                activeTrackColor = Color.Blue,
                                thumbColor = Color.Blue
                            )
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSelect(selectedColorArgb) }) {
                Text("Select")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
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
                    colors = androidx.compose.material3.OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    )
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

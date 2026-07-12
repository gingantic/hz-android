package com.rhnxdev.hzplayer.presentation.settings.components

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.BuildConfig
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
import com.rhnxdev.hzplayer.domain.model.ThemeMode

@Composable
fun ThemeSelectionDialog(
    currentTheme: ThemeMode,
    onDismiss: () -> Unit,
    onSelect: (ThemeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.theme_selection_title)) },
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
                                ThemeMode.LIGHT -> stringResource(R.string.theme_light)
                                ThemeMode.DARK -> stringResource(R.string.theme_dark)
                                ThemeMode.VOID -> stringResource(R.string.theme_void)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun OrientationDialog(
    currentMode: OrientationMode,
    onDismiss: () -> Unit,
    onSelect: (OrientationMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.orientation_selection_title)) },
        text = {
            Column {
                OrientationMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when (mode) {
                                OrientationMode.AUTO -> stringResource(R.string.orientation_auto)
                                OrientationMode.PORTRAIT -> stringResource(R.string.orientation_portrait)
                                OrientationMode.LANDSCAPE -> stringResource(R.string.orientation_landscape)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun DecoderModeDialog(
    currentMode: DecoderMode,
    onDismiss: () -> Unit,
    onSelect: (DecoderMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.decoder_mode_selection_title)) },
        text = {
            Column {
                DecoderMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when (mode) {
                                DecoderMode.AUTO -> stringResource(R.string.decoder_mode_auto)
                                DecoderMode.HARDWARE -> stringResource(R.string.decoder_mode_hardware)
                                DecoderMode.SOFTWARE -> stringResource(R.string.decoder_mode_software)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun ResumeModeDialog(
    currentMode: ResumeMode,
    onDismiss: () -> Unit,
    onSelect: (ResumeMode) -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.resume_mode_selection_title)) },
        text = {
            Column {
                ResumeMode.entries.forEach { mode ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { onSelect(mode) }
                            .padding(vertical = 12.dp, horizontal = 16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = currentMode == mode,
                            onClick = { onSelect(mode) }
                        )
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = when (mode) {
                                ResumeMode.NONE -> stringResource(R.string.resume_mode_none)
                                ResumeMode.ASK -> stringResource(R.string.resume_mode_ask)
                                ResumeMode.ALWAYS -> stringResource(R.string.resume_mode_always)
                            },
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun ColorPickerDialog(
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
    LaunchedEffect(selectedColorArgb) {
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
        title = { Text(stringResource(R.string.color_picker_title)) },
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
                        BasicTextField(
                            value = hexInput,
                            onValueChange = onHexChanged,
                            textStyle = MaterialTheme.typography.titleMedium.copy(
                                color = contentColor,
                                fontWeight = FontWeight.Bold
                            ),
                            cursorBrush = SolidColor(contentColor),
                            singleLine = true,
                            modifier = Modifier.width(70.dp)
                        )
                    }
                }

                // Grid of Presets
                Text(
                    text = stringResource(R.string.color_presets),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurface
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
                        text = stringResource(R.string.color_custom_rgb),
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSurface
                    )

                    // Red Slider
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            Text(stringResource(R.string.color_red), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${redValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Slider(
                            value = redValue,
                            onValueChange = { redValue = it },
                            valueRange = 0f..255f,
                            colors = SliderDefaults.colors(
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
                            Text(stringResource(R.string.color_green), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${greenValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Slider(
                            value = greenValue,
                            onValueChange = { greenValue = it },
                            valueRange = 0f..255f,
                            colors = SliderDefaults.colors(
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
                            Text(stringResource(R.string.color_blue), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Text("${blueValue.toInt()}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurface)
                        }
                        Slider(
                            value = blueValue,
                            onValueChange = { blueValue = it },
                            valueRange = 0f..255f,
                            colors = SliderDefaults.colors(
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
                Text(stringResource(R.string.dialog_select))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun OpenSubtitlesApiKeyDialog(
    currentKey: String,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var text by remember(currentKey) { mutableStateOf(currentKey) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.opensubtitles_title)) },
        text = {
            Column {
                Text(
                    text = stringResource(R.string.opensubtitles_desc),
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(bottom = 8.dp),
                )
                OutlinedTextField(
                    value = text,
                    onValueChange = { text = it },
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.opensubtitles_placeholder)) },
                    modifier = Modifier.fillMaxWidth(),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        focusedLabelColor = MaterialTheme.colorScheme.primary,
                    )
                )
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(text.trim()) }) {
                Text(stringResource(R.string.dialog_save))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_cancel))
            }
        }
    )
}

@Composable
fun AboutDialog(
    onDismiss: () -> Unit,
) {
    val year = java.util.Calendar.getInstance().get(java.util.Calendar.YEAR)
    val tech = listOf(
        stringResource(R.string.about_tech_kotlin),
        stringResource(R.string.about_tech_compose),
        stringResource(R.string.about_tech_exoplayer),
        stringResource(R.string.about_tech_room),
        stringResource(R.string.about_tech_hilt),
    )
    val version = "v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})"
    val buildDate = "${BuildConfig.BUILD_DATE} ${BuildConfig.BUILD_TIME}"

    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Info,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(28.dp),
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = stringResource(R.string.about_title),
                        style = MaterialTheme.typography.titleLarge,
                    )
                    Text(
                        text = version,
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        text = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Text(
                    text = stringResource(R.string.about_tagline),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary,
                )
                Text(
                    text = stringResource(R.string.about_description),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Text(
                    text = stringResource(R.string.about_build_date, buildDate),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.about_built_with),
                    style = MaterialTheme.typography.labelLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                tech.forEach { techName ->
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Check,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(text = techName, style = MaterialTheme.typography.bodyMedium)
                    }
                }
                HorizontalDivider()
                Text(
                    text = stringResource(R.string.about_copyright, year),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.dialog_close))
            }
        },
    )
}

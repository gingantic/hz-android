package com.rhnxdev.hzplayer.presentation.settings.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
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
) = EnumSelectionDialog(
    current = currentTheme,
    titleRes = R.string.theme_selection_title,
    label = {
        when (it) {
            ThemeMode.LIGHT -> stringResource(R.string.theme_light)
            ThemeMode.DARK -> stringResource(R.string.theme_dark)
            ThemeMode.VOID -> stringResource(R.string.theme_void)
            ThemeMode.SYSTEM -> stringResource(R.string.theme_system)
        }
    },
    onDismiss = onDismiss,
    onSelect = onSelect,
)

@Composable
fun OrientationDialog(
    currentMode: OrientationMode,
    onDismiss: () -> Unit,
    onSelect: (OrientationMode) -> Unit,
) = EnumSelectionDialog(
    current = currentMode,
    titleRes = R.string.orientation_selection_title,
    label = {
        when (it) {
            OrientationMode.AUTO -> stringResource(R.string.orientation_auto)
            OrientationMode.PORTRAIT -> stringResource(R.string.orientation_portrait)
            OrientationMode.LANDSCAPE -> stringResource(R.string.orientation_landscape)
        }
    },
    onDismiss = onDismiss,
    onSelect = onSelect,
)

@Composable
fun DecoderModeDialog(
    currentMode: DecoderMode,
    onDismiss: () -> Unit,
    onSelect: (DecoderMode) -> Unit,
) = EnumSelectionDialog(
    current = currentMode,
    titleRes = R.string.decoder_mode_selection_title,
    label = {
        when (it) {
            DecoderMode.AUTO -> stringResource(R.string.decoder_mode_auto)
            DecoderMode.HARDWARE -> stringResource(R.string.decoder_mode_hardware)
            DecoderMode.SOFTWARE -> stringResource(R.string.decoder_mode_software)
        }
    },
    onDismiss = onDismiss,
    onSelect = onSelect,
)

@Composable
fun ResumeModeDialog(
    currentMode: ResumeMode,
    onDismiss: () -> Unit,
    onSelect: (ResumeMode) -> Unit,
) = EnumSelectionDialog(
    current = currentMode,
    titleRes = R.string.resume_mode_selection_title,
    label = {
        when (it) {
            ResumeMode.NONE -> stringResource(R.string.resume_mode_none)
            ResumeMode.ASK -> stringResource(R.string.resume_mode_ask)
            ResumeMode.ALWAYS -> stringResource(R.string.resume_mode_always)
        }
    },
    onDismiss = onDismiss,
    onSelect = onSelect,
)

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
    label = { stringResource(it.labelRes()) },
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
    label = { stringResource(it.labelRes()) },
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
    label = { stringResource(it.labelRes()) },
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
    label = { stringResource(it.labelRes()) },
    onDismiss = onDismiss,
    onSelect = onSelect,
)

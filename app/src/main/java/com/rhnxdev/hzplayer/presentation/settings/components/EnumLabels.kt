package com.rhnxdev.hzplayer.presentation.settings.components

import androidx.annotation.StringRes
import com.rhnxdev.hzplayer.R
import com.rhnxdev.hzplayer.domain.model.DecoderMode
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.ResumeMode
import com.rhnxdev.hzplayer.domain.model.ThemeMode

/**
 * Label resources for the settings enums.
 *
 * Plain `@StringRes` accessors rather than `@Composable` helpers so one mapping
 * serves both the settings rows in `SettingsScreen` (used as a subtitle) and the
 * selection dialogs in `SettingsDialogs` (used as an option label).
 */
@StringRes
fun ThemeMode.labelRes(): Int = when (this) {
    ThemeMode.LIGHT -> R.string.theme_light
    ThemeMode.DARK -> R.string.theme_dark
    ThemeMode.VOID -> R.string.theme_void
    ThemeMode.SYSTEM -> R.string.theme_system
}

@StringRes
fun OrientationMode.labelRes(): Int = when (this) {
    OrientationMode.AUTO -> R.string.orientation_auto
    OrientationMode.PORTRAIT -> R.string.orientation_portrait
    OrientationMode.LANDSCAPE -> R.string.orientation_landscape
}

@StringRes
fun DecoderMode.labelRes(): Int = when (this) {
    DecoderMode.AUTO -> R.string.decoder_mode_auto
    DecoderMode.HARDWARE -> R.string.decoder_mode_hardware
    DecoderMode.SOFTWARE -> R.string.decoder_mode_software
}

@StringRes
fun ResumeMode.labelRes(): Int = when (this) {
    ResumeMode.NONE -> R.string.resume_mode_none
    ResumeMode.ASK -> R.string.resume_mode_ask
    ResumeMode.ALWAYS -> R.string.resume_mode_always
}

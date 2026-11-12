package com.rhnxdev.hzplayer.core.designsystem

import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Height of the floating mini player, 0 when it is not showing.
 *
 * The mini player is drawn over the tab content, so bottom-anchored controls
 * (FABs) add this as extra bottom padding to stay clear of it.
 */
val LocalMiniPlayerInset = compositionLocalOf { 0.dp }

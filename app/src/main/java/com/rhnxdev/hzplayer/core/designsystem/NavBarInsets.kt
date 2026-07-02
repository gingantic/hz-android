package com.rhnxdev.hzplayer.core.designsystem

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBarsIgnoringVisibility
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.statusBarsIgnoringVisibility
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * Returns the status-bar top inset in [Dp].
 *
 * Uses [WindowInsets.statusBarsIgnoringVisibility] which:
 * - Returns the **maximum** height the status bar occupies, regardless of
 *   whether it is currently shown or hidden/animating.
 *   → The HUD top-bar never slides when the notification bar appears/disappears.
 * - Is a fully reactive Compose state that updates immediately when the
 *   screen is rotated (portrait ↔ landscape have different status bar sizes).
 *   → The HUD correctly recalculates layout on every orientation change.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun stableStatusBarTopDp(): Dp {
    val density = LocalDensity.current
    return with(density) {
        WindowInsets.statusBarsIgnoringVisibility.getTop(density).toDp()
    }
}

/**
 * Navigation-bar [PaddingValues] for all 4 edges, ignoring bar visibility.
 *
 * Uses [WindowInsets.navigationBarsIgnoringVisibility] so that:
 * - The HUD bottom/side controls stay fixed even when the 3-button nav bar
 *   slides in or out.
 * - Position is recalculated instantly on rotation.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun stableNavBarPaddingValues(): PaddingValues {
    return WindowInsets.navigationBarsIgnoringVisibility.asPaddingValues()
}

/**
 * Stable nav-bar [PaddingValues] for **horizontal edges only** (start/end).
 *
 * Use inside the main tab pager to respect the side nav-bar inset in landscape
 * without any sliding or jitter when bars appear/disappear.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun stableNavBarHorizontalPadding(): PaddingValues {
    return WindowInsets.navigationBarsIgnoringVisibility
        .only(WindowInsetsSides.Horizontal)
        .asPaddingValues()
}

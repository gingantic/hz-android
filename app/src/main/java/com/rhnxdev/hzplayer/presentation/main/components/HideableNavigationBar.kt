package com.rhnxdev.hzplayer.presentation.main.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsBottomHeight
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarDefaults
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemColors
import androidx.compose.material3.Text
import androidx.compose.material3.surfaceColorAtElevation
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.res.stringResource
import com.rhnxdev.hzplayer.core.components.HideOnScrollState
import com.rhnxdev.hzplayer.core.components.hideBottomOnScroll
import com.rhnxdev.hzplayer.presentation.navigation.bottomNavDestinations

/**
 * Portrait bottom navigation that slides away with the list scroll.
 *
 * Only the bar itself hides. The system navigation-bar inset stays as a strip below it, which
 * fades from the bar colour to the page colour, so content never slides under the gesture area.
 *
 * @param state — shared scroll-hide state
 * @param selectedIndex — index in [bottomNavDestinations] of the current tab
 * @param onSelect — called with the tapped tab index
 * @param itemColors — item colours, shared with the landscape rail
 */
@Composable
fun HideableNavigationBar(
    state: HideOnScrollState,
    selectedIndex: Int,
    onSelect: (Int) -> Unit,
    itemColors: NavigationBarItemColors,
    modifier: Modifier = Modifier,
) {
    val colorScheme = MaterialTheme.colorScheme
    val barColor = colorScheme.surface
    // Same tint the bar draws at its default elevation, so the strip matches it exactly.
    val stripColor = colorScheme.surfaceColorAtElevation(NavigationBarDefaults.Elevation)
    val pageColor = colorScheme.background

    Column(modifier = modifier) {
        NavigationBar(
            modifier = Modifier.hideBottomOnScroll(state),
            containerColor = barColor,
            windowInsets = NavigationBarDefaults.windowInsets.only(WindowInsetsSides.Horizontal),
        ) {
            bottomNavDestinations.forEachIndexed { index, dest ->
                NavigationBarItem(
                    selected = selectedIndex == index,
                    onClick = { onSelect(index) },
                    icon = {
                        Icon(
                            dest.icon,
                            contentDescription = stringResource(dest.labelRes),
                        )
                    },
                    label = { Text(stringResource(dest.labelRes)) },
                    colors = itemColors,
                )
            }
        }
        Spacer(
            modifier = Modifier
                .fillMaxWidth()
                .windowInsetsBottomHeight(WindowInsets.navigationBars)
                .drawBehind { drawRect(lerp(stripColor, pageColor, state.hiddenFraction)) },
        )
    }
}

package com.rhnxdev.hzplayer.core.components

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewLightDark
import androidx.compose.ui.unit.dp
import com.rhnxdev.hzplayer.core.designsystem.Spacing
import com.rhnxdev.hzplayer.presentation.theme.HzPlayerTheme

/**
 * Uniform toolbar used across all screens.
 *
 * Supports inline search mode: when [searchQuery] is non-null the title area
 * becomes a search text field. When it's null and [onSearchToggle] is provided
 * a search icon appears as an action.
 *
 * @param title — screen title (hidden when searching)
 * @param showBack — show back arrow
 * @param onBack — back action
 * @param actionIcon — optional right-side icon (shown before actions)
 * @param onAction — action icon click
 * @param searchQuery — null = idle; non-null = show search field with this value
 * @param searchPlaceholder — placeholder text for the search field
 * @param onSearchQueryChanged — search field text changes
 * @param onSearchToggle — called when user taps the search icon to enter search mode (null = no search icon)
 * @param onSearchClose — called when user taps close/back in search mode
 * @param actions — additional action buttons (shown after the search icon)
 */
@Composable
fun HzPlayerTopBar(
    title: String,
    showBack: Boolean = false,
    onBack: () -> Unit = {},
    actionIcon: ImageVector? = null,
    onAction: () -> Unit = {},
    searchQuery: String? = null,
    searchPlaceholder: String = "Search...",
    onSearchQueryChanged: (String) -> Unit = {},
    onSearchToggle: (() -> Unit)? = null,
    onSearchClose: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    modifier: Modifier = Modifier,
) {
    val focusManager = LocalFocusManager.current
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the search field when it appears (VLC-like UX)
    LaunchedEffect(searchQuery) {
        if (searchQuery != null) {
            focusRequester.requestFocus()
        }
    }

    Row(
        modifier = modifier
            .fillMaxWidth()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Back arrow or spacer
        if (showBack) {
            SpacingExtra(Spacing.xs)
            IconButton(onClick = onBack) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Back",
                )
            }
            SpacingExtra(Spacing.md)
        } else if (searchQuery != null) {
            // In search mode, show a close/back arrow to exit search
            SpacingExtra(Spacing.xs)
            IconButton(onClick = {
                onSearchClose()
                focusManager.clearFocus()
            }) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.ArrowBack,
                    contentDescription = "Close search",
                )
            }
            SpacingExtra(Spacing.sm)
        } else {
            SpacingExtra(Spacing.lg)
        }

        // Title or search field
        if (searchQuery != null) {
            TextField(
                value = searchQuery,
                onValueChange = onSearchQueryChanged,
                modifier = Modifier
                    .weight(1f)
                    .focusRequester(focusRequester),
                placeholder = {
                    Text(
                        text = searchPlaceholder,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                leadingIcon = {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = null,
                        modifier = Modifier.size(20.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                },
                trailingIcon = {
                    if (searchQuery.isNotEmpty()) {
                        IconButton(onClick = { onSearchQueryChanged("") }) {
                            Icon(
                                imageVector = Icons.Default.Clear,
                                contentDescription = "Clear",
                            )
                        }
                    }
                },
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyLarge,
                colors = TextFieldDefaults.colors(
                    focusedContainerColor = MaterialTheme.colorScheme.surface,
                    unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                    focusedIndicatorColor = MaterialTheme.colorScheme.primary,
                    unfocusedIndicatorColor = MaterialTheme.colorScheme.outlineVariant,
                    cursorColor = MaterialTheme.colorScheme.primary,
                ),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(
                    onSearch = { focusManager.clearFocus() }
                ),
            )
        } else {
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.weight(1f),
            )
        }

        // Actions
        Row(
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actions()
            if (searchQuery == null && onSearchToggle != null) {
                IconButton(onClick = onSearchToggle) {
                    Icon(
                        imageVector = Icons.Default.Search,
                        contentDescription = "Search",
                    )
                }
            }
            if (actionIcon != null && searchQuery == null) {
                IconButton(onClick = onAction) {
                    Icon(
                        imageVector = actionIcon,
                        contentDescription = null,
                    )
                }
            }
            SpacingExtra(Spacing.md)
        }
    }
}

/**
 * Thin wrapper that composes a [Spacer] with the given [width] — kept as a private
 * helper so the row layout is readable without importing Spacer everywhere.
 */
@Composable
private fun SpacingExtra(width: androidx.compose.ui.unit.Dp) {
    androidx.compose.foundation.layout.Spacer(modifier = Modifier.width(width))
}

@PreviewLightDark
@Preview
@Composable
private fun HzPlayerTopBarPreview() {
    HzPlayerTheme {
        HzPlayerTopBar(
            title = "Hz Player",
        )
    }
}

@PreviewLightDark
@Preview
@Composable
private fun HzPlayerTopBarSearchPreview() {
    HzPlayerTheme {
        HzPlayerTopBar(
            title = "Hz Player",
            searchQuery = "test",
            onSearchQueryChanged = {},
            onSearchClose = {},
        )
    }
}

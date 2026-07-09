package com.rhnxdev.hzplayer.core.components

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Reusable scaffold for screens that support inline search via [HzPlayerTopBar].
 *
 * Handles the Column + HzPlayerTopBar + BackHandler wiring that every
 * searchable screen repeats. Call it from any screen composable and
 * pass the content as a slot.
 *
 * @param title         — visible when search is inactive.
 * @param isSearchActive / searchQuery — from the screen's UiState.
 * @param onSearchToggle / onSearchQueryChanged / onClearSearch — from the ViewModel.
 * @param onNavigateUp  — called on back-press when search is *not* active.
 *                        Pass `null` to disable the navigate-up back handler
 *                        (e.g. when the screen has its own back handling).
 * @param searchPlaceholder — placeholder text for the search field.
 * @param fullScreenOverlay — disables back handling when an overlay (player)
 *                           is covering the screen.
 * @param content       — the screen body below the top bar.
 */
@Composable
fun HzPlayerSearchableScaffold(
    title: String,
    isSearchActive: Boolean,
    searchQuery: String,
    onSearchToggle: () -> Unit,
    onSearchQueryChanged: (String) -> Unit,
    onClearSearch: () -> Unit,
    onNavigateUp: (() -> Unit)? = null,
    searchPlaceholder: String = "Search...",
    fullScreenOverlay: Boolean = false,
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back-press: close search first, then navigate up
    val canNavigateUp = onNavigateUp != null
    BackHandler(
        enabled = (isSearchActive || canNavigateUp) && !fullScreenOverlay,
    ) {
        if (isSearchActive) onClearSearch()
        else onNavigateUp?.invoke()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        HzPlayerTopBar(
            title = title,
            showBack = canNavigateUp,
            onBack = { onNavigateUp?.invoke() },
            searchQuery = if (isSearchActive) searchQuery else null,
            searchPlaceholder = searchPlaceholder,
            onSearchQueryChanged = onSearchQueryChanged,
            onSearchToggle = onSearchToggle,
            onSearchClose = onClearSearch,
            actions = actions,
        )
        content()
    }
}

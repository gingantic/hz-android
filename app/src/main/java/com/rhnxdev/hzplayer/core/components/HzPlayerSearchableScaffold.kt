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
 * @param selectionCount — number of multi-selected items; above 0 the selection top bar
 *                         replaces the normal one and back-press exits selection first.
 * @param onExitSelection / onSelectAll — selection top bar callbacks.
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
    isActive: Boolean = true,
    selectionCount: Int = 0,
    onExitSelection: () -> Unit = {},
    onSelectAll: () -> Unit = {},
    actions: @Composable RowScope.() -> Unit = {},
    content: @Composable ColumnScope.() -> Unit,
) {
    // Back-press: exit selection first, then close search, then navigate up
    val canNavigateUp = onNavigateUp != null
    val isSelecting = selectionCount > 0
    BackHandler(
        enabled = isActive && (isSelecting || isSearchActive || canNavigateUp) && !fullScreenOverlay,
    ) {
        if (isSelecting) onExitSelection()
        else if (isSearchActive) onClearSearch()
        else onNavigateUp?.invoke()
    }

    Column(modifier = Modifier.fillMaxSize()) {
        if (isSelecting) {
            SelectionTopBar(
                count = selectionCount,
                onExit = onExitSelection,
                onSelectAll = onSelectAll,
            )
        } else {
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
        }
        content()
    }
}

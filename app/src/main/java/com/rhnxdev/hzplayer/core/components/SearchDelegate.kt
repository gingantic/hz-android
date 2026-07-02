package com.rhnxdev.hzplayer.core.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reusable search state holder.
 *
 * Any ViewModel can hold an instance and expose its [searchQuery] /
 * [isSearchActive] flows to the screen UiState. The three action
 * methods are wired directly to the UI callbacks — zero per-screen
 * boilerplate.
 */
class SearchDelegate {

    private val _searchQuery = MutableStateFlow("")
    /** Current search text (empty string when idle). */
    val searchQuery: StateFlow<String> = _searchQuery.asStateFlow()

    private val _isSearchActive = MutableStateFlow(false)
    /** `true` while the search field is visible. */
    val isSearchActive: StateFlow<Boolean> = _isSearchActive.asStateFlow()

    /** Enter search mode with an empty query. */
    fun toggle() {
        _searchQuery.value = ""
        _isSearchActive.value = true
    }

    /** Update the current query text (search stays active). */
    fun queryChanged(query: String) {
        _searchQuery.value = query
    }

    /** Exit search mode and reset the query. */
    fun clear() {
        _searchQuery.value = ""
        _isSearchActive.value = false
    }
}

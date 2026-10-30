package com.rhnxdev.hzplayer.core.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reusable multi-select state holder.
 *
 * A ViewModel owns one instance and exposes [selectedKeys] to its screen. Selection
 * mode is simply "the set is non-empty", so deselecting the last item exits it.
 * Keys must be stable per item (a path or URI), never a hash-derived id.
 */
class SelectionDelegate {

    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Currently selected item keys; empty when not in selection mode. */
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    /** True while at least one item is selected. */
    val isActive: Boolean get() = _selectedKeys.value.isNotEmpty()

    /** Enter selection mode with [key] selected (adds to an existing selection). */
    fun start(key: String) {
        _selectedKeys.value = _selectedKeys.value + key
    }

    /** Flip [key]; removing the last key leaves selection mode. */
    fun toggle(key: String) {
        val current = _selectedKeys.value
        _selectedKeys.value = if (key in current) current - key else current + key
    }

    /** Select every key in [keys], keeping what is already selected. */
    fun selectAll(keys: Set<String>) {
        _selectedKeys.value = _selectedKeys.value + keys
    }

    /** Drop selected keys that are not in [keys]; keeps the count equal to what an action touches. */
    fun retain(keys: Set<String>) {
        val current = _selectedKeys.value
        val kept = current.intersect(keys)
        if (kept.size != current.size) _selectedKeys.value = kept
    }

    /** Leave selection mode. */
    fun clear() {
        if (_selectedKeys.value.isNotEmpty()) _selectedKeys.value = emptySet()
    }
}

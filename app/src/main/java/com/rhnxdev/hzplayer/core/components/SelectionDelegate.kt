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

    /** Keys the owner last reported as listed, see [retain]. */
    private var listed: Set<String> = emptySet()
    private val _allListedSelected = MutableStateFlow(false)
    /** True when every listed key is selected; the select-all button shows "deselect" then. */
    val allListedSelected: StateFlow<Boolean> = _allListedSelected.asStateFlow()

    /** True while at least one item is selected. */
    val isActive: Boolean get() = _selectedKeys.value.isNotEmpty()

    /** Enter selection mode with [key] selected (adds to an existing selection). */
    fun start(key: String) {
        publish(_selectedKeys.value + key)
    }

    /** Flip [key]; removing the last key leaves selection mode. */
    fun toggle(key: String) {
        val current = _selectedKeys.value
        publish(if (key in current) current - key else current + key)
    }

    /**
     * Select every key in [keys], keeping what is already selected. When all of [keys]
     * are already selected, deselect them instead.
     */
    fun toggleAll(keys: Set<String>) {
        val current = _selectedKeys.value
        publish(if (keys.isNotEmpty() && current.containsAll(keys)) current - keys else current + keys)
    }

    /**
     * Record [keys] as the listed items and drop selected keys that are not in them;
     * keeps the count equal to what an action touches.
     */
    fun retain(keys: Set<String>) {
        listed = keys
        publish(_selectedKeys.value.intersect(keys))
    }

    /** Leave selection mode. */
    fun clear() {
        if (_selectedKeys.value.isNotEmpty()) publish(emptySet())
    }

    private fun publish(selected: Set<String>) {
        _selectedKeys.value = selected
        _allListedSelected.value = listed.isNotEmpty() && selected.containsAll(listed)
    }
}

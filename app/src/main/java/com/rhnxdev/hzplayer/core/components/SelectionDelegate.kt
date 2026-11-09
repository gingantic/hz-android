package com.rhnxdev.hzplayer.core.components

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reusable multi-select state holder.
 *
 * A ViewModel owns one instance and exposes [isSelecting] and [selectedKeys] to its
 * screen. Selection mode stays open even when all items are unselected (0 selected),
 * and only exits when [clear] is called. Keys must be stable per item (a path or URI),
 * never a hash-derived id.
 */
class SelectionDelegate {

    private val _isSelecting = MutableStateFlow(false)
    /** True while selection mode is open, even when 0 items are currently selected. */
    val isSelecting: StateFlow<Boolean> = _isSelecting.asStateFlow()

    private val _selectedKeys = MutableStateFlow<Set<String>>(emptySet())
    /** Currently selected item keys. */
    val selectedKeys: StateFlow<Set<String>> = _selectedKeys.asStateFlow()

    /** Keys the owner last reported as listed, see [retain]. */
    private var listed: Set<String> = emptySet()
    private val _allListedSelected = MutableStateFlow(false)
    /** True when every listed key is selected; the select-all button shows "deselect" then. */
    val allListedSelected: StateFlow<Boolean> = _allListedSelected.asStateFlow()

    /** True while selection mode is open. */
    val isActive: Boolean get() = _isSelecting.value

    /** Enter selection mode with [key] selected (adds to an existing selection). */
    fun start(key: String) {
        _isSelecting.value = true
        publish(_selectedKeys.value + key)
    }

    /** Flip [key] without leaving selection mode. */
    fun toggle(key: String) {
        val current = _selectedKeys.value
        publish(if (key in current) current - key else current + key)
    }

    /**
     * Select every key in [keys], keeping what is already selected. When all of [keys]
     * are already selected, deselect them instead while staying in selection mode.
     */
    fun toggleAll(keys: Set<String>) {
        if (keys.isNotEmpty()) _isSelecting.value = true
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
        _isSelecting.value = false
        if (_selectedKeys.value.isNotEmpty() || _allListedSelected.value) {
            publish(emptySet())
        }
    }

    private fun publish(selected: Set<String>) {
        _selectedKeys.value = selected
        _allListedSelected.value = listed.isNotEmpty() && selected.containsAll(listed)
    }
}

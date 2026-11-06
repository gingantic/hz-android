package com.rhnxdev.hzplayer.core.components

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectionDelegateTest {

    @Test
    fun startsInactive() {
        val selection = SelectionDelegate()
        assertFalse(selection.isActive)
        assertEquals(emptySet<String>(), selection.selectedKeys.value)
    }

    @Test
    fun startEntersSelectionModeWithThatKey() {
        val selection = SelectionDelegate()
        selection.start("/a")
        assertTrue(selection.isActive)
        assertEquals(setOf("/a"), selection.selectedKeys.value)
    }

    @Test
    fun toggleAddsThenRemoves() {
        val selection = SelectionDelegate()
        selection.start("/a")
        selection.toggle("/b")
        assertEquals(setOf("/a", "/b"), selection.selectedKeys.value)
        selection.toggle("/b")
        assertEquals(setOf("/a"), selection.selectedKeys.value)
    }

    @Test
    fun togglingTheLastKeyLeavesSelectionMode() {
        val selection = SelectionDelegate()
        selection.start("/a")
        selection.toggle("/a")
        assertFalse(selection.isActive)
    }

    @Test
    fun toggleAllKeepsExistingSelection() {
        val selection = SelectionDelegate()
        selection.start("/a")
        selection.toggleAll(setOf("/b", "/c"))
        assertEquals(setOf("/a", "/b", "/c"), selection.selectedKeys.value)
    }

    @Test
    fun retainDropsKeysThatAreNoLongerVisible() {
        val selection = SelectionDelegate()
        selection.toggleAll(setOf("/a", "/b", "/c"))
        selection.retain(setOf("/b", "/c", "/d"))
        // "/d" was never selected, so it must not appear.
        assertEquals(setOf("/b", "/c"), selection.selectedKeys.value)
    }

    @Test
    fun retainingNothingLeavesSelectionMode() {
        val selection = SelectionDelegate()
        selection.start("/a")
        selection.retain(emptySet())
        assertFalse(selection.isActive)
    }

    @Test
    fun clearEmptiesTheSelection() {
        val selection = SelectionDelegate()
        selection.toggleAll(setOf("/a", "/b"))
        selection.clear()
        assertFalse(selection.isActive)
        assertEquals(emptySet<String>(), selection.selectedKeys.value)
    }

    @Test
    fun clearOnEmptySelectionIsANoOp() {
        val selection = SelectionDelegate()
        selection.clear()
        assertFalse(selection.isActive)
    }
}

package com.rhnxdev.hzplayer.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SelectorHeuristicsTest {

    @Test
    fun testRandomTokensAreFlagged() {
        assertTrue(SelectorHeuristics.looksRandom("ad_x7f9k2"))
        assertTrue(SelectorHeuristics.looksRandom("adbox_7fa21"))
        assertTrue(SelectorHeuristics.looksRandom("wrapper-9ks2x"))
        assertTrue(SelectorHeuristics.looksRandom("a1b2c3d4"))        // hex blob
        assertTrue(SelectorHeuristics.looksRandom("x7f9k2qp"))        // vowel-starved base36
        assertTrue(SelectorHeuristics.looksRandom("483726"))          // pure numeric
        assertTrue(SelectorHeuristics.looksRandom("3f2504e0-4f89-41d3-9a0c-0305e82c3301")) // UUID
    }

    @Test
    fun testStableWordsAreNotFlagged() {
        assertFalse(SelectorHeuristics.looksRandom("ad"))
        assertFalse(SelectorHeuristics.looksRandom("ads"))
        assertFalse(SelectorHeuristics.looksRandom("banner"))
        assertFalse(SelectorHeuristics.looksRandom("sidebar"))
        assertFalse(SelectorHeuristics.looksRandom("sponsor"))
        assertFalse(SelectorHeuristics.looksRandom("promo"))
        assertFalse(SelectorHeuristics.looksRandom("container"))
        assertFalse(SelectorHeuristics.looksRandom("widget"))
    }

    @Test
    fun testRealWordsOutsideAllowlistAreNotFlagged() {
        assertFalse(SelectorHeuristics.looksRandom("header"))
        assertFalse(SelectorHeuristics.looksRandom("footer"))
        assertFalse(SelectorHeuristics.looksRandom("content"))
        assertFalse(SelectorHeuristics.looksRandom("main-content"))
        assertFalse(SelectorHeuristics.looksRandom("navigation"))
    }

    @Test
    fun testShortTokensAreNeverRandom() {
        assertFalse(SelectorHeuristics.looksRandom("abc"))
        assertFalse(SelectorHeuristics.looksRandom("x7f"))
    }

    @Test
    fun testStemExtraction() {
        assertEquals("adbox", SelectorHeuristics.stemOf("adbox_7fa21"))
        assertEquals("ad-slot__container", SelectorHeuristics.stemOf("ad-slot__container_x7f9k2"))
    }

    @Test
    fun testStemNullWhenNoStableStem() {
        // tail random but stem too short (< 4 chars)
        assertNull(SelectorHeuristics.stemOf("ad_x7f9k2"))
        // no random tail at all
        assertNull(SelectorHeuristics.stemOf("ad"))
        assertNull(SelectorHeuristics.stemOf("main-content"))
    }
}

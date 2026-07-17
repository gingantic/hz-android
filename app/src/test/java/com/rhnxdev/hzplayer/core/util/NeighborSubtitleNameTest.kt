package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Guards neighbor-subtitle name matching: exact base plus language-tagged variants
 * ("movie.en.srt") must match, foreign files and non-subtitle extensions must not.
 */
class NeighborSubtitleNameTest {

    private val base = "The Movie (2024)"

    @Test
    fun exactBaseMatches() {
        assertTrue(isNeighborSubtitleName("The Movie (2024).srt", base))
        assertTrue(isNeighborSubtitleName("THE MOVIE (2024).ASS", base))
    }

    @Test
    fun languageTaggedVariantsMatch() {
        assertTrue(isNeighborSubtitleName("The Movie (2024).en.srt", base))
        assertTrue(isNeighborSubtitleName("The Movie (2024).eng.forced.ass", base))
    }

    @Test
    fun foreignOrNonSubtitleDoesNotMatch() {
        assertFalse(isNeighborSubtitleName("Other Movie.srt", base))
        assertFalse(isNeighborSubtitleName("The Movie (2024).mkv", base))
        assertFalse(isNeighborSubtitleName("The Movie (2024)-extra.srt", base))
    }
}

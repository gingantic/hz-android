package com.rhnxdev.hzplayer.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Test

class VideoThumbnailTest {

    @Test
    fun frameTimeUs_is_40_percent_in_microseconds() {
        // 10s clip -> 4s -> 4_000_000 us
        assertEquals(4_000_000L, frameTimeUs(10_000L))
        assertEquals(0L, frameTimeUs(0L))
    }
}

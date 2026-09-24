package com.rhnxdev.hzplayer.data.datasource.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FfmpegNativeEngineSeekFilterTest {
    @Test
    fun backwardSeek_rejectsPositionsStillAboveTarget() {
        assertFalse(FfmpegNativeEngine.hasSeekLanded(10_501L, 10_000L, seekBackward = true))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(10_500L, 10_000L, seekBackward = true))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(10_000L, 10_000L, seekBackward = true))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(7_000L, 10_000L, seekBackward = true))
    }

    @Test
    fun forwardSeek_rejectsPositionsStillBelowTarget() {
        assertFalse(FfmpegNativeEngine.hasSeekLanded(9_499L, 10_000L, seekBackward = false))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(9_500L, 10_000L, seekBackward = false))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(10_000L, 10_000L, seekBackward = false))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(13_000L, 10_000L, seekBackward = false))
    }

    @Test
    fun seekAtZero_usesTheSameDirectionRules() {
        assertFalse(FfmpegNativeEngine.hasSeekLanded(501L, 0L, seekBackward = true))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(500L, 0L, seekBackward = true))
        assertFalse(FfmpegNativeEngine.hasSeekLanded(-501L, 0L, seekBackward = false))
        assertTrue(FfmpegNativeEngine.hasSeekLanded(-500L, 0L, seekBackward = false))
    }
}

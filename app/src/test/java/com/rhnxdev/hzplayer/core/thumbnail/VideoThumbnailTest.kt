package com.rhnxdev.hzplayer.core.thumbnail

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.util.concurrent.TimeUnit

class VideoThumbnailTest {

    @get:Rule
    val tempFolder = TemporaryFolder()

    @Test
    fun frameTimeUs_is_40_percent_in_microseconds() {
        // 10s clip -> 4s -> 4_000_000 us
        assertEquals(4_000_000L, frameTimeUs(10_000L))
        assertEquals(0L, frameTimeUs(0L))
    }

    @Test
    fun scaledDimensions_clamps_longest_edge_to_max_width_preserving_aspect() {
        val (w, h) = scaledDimensions(1920, 1080)
        assertEquals(THUMB_MAX_WIDTH, w)
        // 512 * 1080 / 1920 = 288 -> 16:9 preserved
        assertEquals(288, h)
    }

    @Test
    fun scaledDimensions_leaves_small_sources_untouched() {
        val (w, h) = scaledDimensions(320, 240)
        assertEquals(320, w)
        assertEquals(240, h)
    }

    @Test
    fun webp_quality_is_within_valid_range() {
        assertTrue(THUMB_WEBP_QUALITY in 0..100)
    }

    @Test
    fun pruneOldThumbnails_removes_stale_keeps_recent() {
        val dir = tempFolder.newFolder("video_thumbs")
        val now = System.currentTimeMillis()
        val old200Days = now - TimeUnit.DAYS.toMillis(200)

        val stale = java.io.File(dir, "aaa_1.webp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(old200Days)
        }
        val staleMarker = java.io.File(dir, "bbb_2.webp.f2.fail").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(old200Days)
        }
        val recent = java.io.File(dir, "ccc_3.webp").apply {
            writeBytes(byteArrayOf(1))
            setLastModified(now)
        }

        val deleted = pruneOldThumbnails(dir, THUMB_MAX_AGE_MS, now)

        assertEquals(2, deleted)
        assertFalse(stale.exists())
        assertFalse(staleMarker.exists())
        assertTrue(recent.exists())
    }

    @Test
    fun pruneOldThumbnails_missing_dir_is_noop() {
        val missing = java.io.File(tempFolder.root, "does_not_exist")
        assertEquals(0, pruneOldThumbnails(missing, THUMB_MAX_AGE_MS))
    }
}

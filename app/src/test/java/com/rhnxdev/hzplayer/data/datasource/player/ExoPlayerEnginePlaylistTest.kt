package com.rhnxdev.hzplayer.data.datasource.player

import androidx.media3.common.MediaItem
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the P0 regression where adding an external subtitle (or retrying)
 * called `player.setMediaItem(...)` and wiped the active playlist + lost the
 * resume position. The engine now rebuilds the full item list and restores
 * the current index + position. These tests exercise the pure rebuild fn
 * ([rebuildPlaylistForSubtitleSwap]) with no real ExoPlayer.
 */
@RunWith(RobolectricTestRunner::class)
class ExoPlayerEnginePlaylistTest {

    private fun item(uri: String, title: String): MediaItem =
        MediaItem.Builder().setUri(uri).setMediaMetadata(
            androidx.media3.common.MediaMetadata.Builder().setTitle(title).build(),
        ).build()

    @Test
    fun singleItem_preservedIndexAndPosition() {
        val cur = item("smb://h/v.mkv", "V")
        val result = rebuildPlaylistForSubtitleSwap(
            playlist = listOf(cur),
            currentUri = "smb://h/v.mkv",
            currentTitle = "V",
            currentIndex = 0,
            startPositionMs = 42_000L,
            subtitleConfigs = emptyList(),
        ) { uri, title, subs -> item(uri, title) }

        assertEquals(1, result.items.size)
        assertEquals(0, result.startIndex)
        assertEquals(42_000L, result.startPositionMs)
        assertEquals("smb://h/v.mkv", result.items[0].localConfiguration?.uri.toString())
    }

    @Test
    fun playlist_otherItemsUntouched() {
        val a = item("smb://h/a.mkv", "A")
        val b = item("smb://h/b.mkv", "B")
        val c = item("smb://h/c.mkv", "C")
        val result = rebuildPlaylistForSubtitleSwap(
            playlist = listOf(a, b, c),
            currentUri = "smb://h/b.mkv",
            currentTitle = "B",
            currentIndex = 1,
            startPositionMs = 7_000L,
            subtitleConfigs = emptyList(),
        ) { uri, title, subs -> item(uri, title) }

        assertEquals(3, result.items.size)
        // Other items kept by reference — not rebuilt.
        assertEquals(a, result.items[0])
        assertEquals(c, result.items[2])
        // Current item (idx 1) swapped to carry the new subtitle config.
        assertEquals("smb://h/b.mkv", result.items[1].localConfiguration?.uri.toString())
        // Current item index/position preserved.
        assertEquals(1, result.startIndex)
        assertEquals(7_000L, result.startPositionMs)
    }

    @Test
    fun nullPlaylist_fallsBackToSingleCurrentItem() {
        val result = rebuildPlaylistForSubtitleSwap(
            playlist = null,
            currentUri = "smb://h/v.mkv",
            currentTitle = "V",
            currentIndex = 2, // out-of-range when playlist is null → collapses to single item
            startPositionMs = 0L,
            subtitleConfigs = emptyList(),
        ) { uri, title, subs -> item(uri, title) }

        assertEquals(1, result.items.size)
        assertEquals("smb://h/v.mkv", result.items[0].localConfiguration?.uri.toString())
    }
}

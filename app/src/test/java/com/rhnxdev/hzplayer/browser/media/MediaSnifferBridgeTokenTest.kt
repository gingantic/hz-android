package com.rhnxdev.hzplayer.browser.media

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The bridge is reachable from every frame; only calls carrying the token the
 * injected main-frame script knows may reach the callbacks.
 */
class MediaSnifferBridgeTokenTest {

    private val token = "test-token-123"

    private fun bridge(
        onMediaDetected: (String, String, String, Map<String, String>) -> Unit = { _, _, _, _ -> },
        onPlaybackStateChanged: (Boolean) -> Unit = {},
        onPipRequested: () -> Unit = {},
    ) = MediaSnifferBridge(
        tokenValidator = { it == token },
        onMediaDetected = onMediaDetected,
        onPlaybackStateChanged = onPlaybackStateChanged,
        onPipRequested = onPipRequested,
    )

    @Test
    fun mediaFoundWithWrongToken_isDropped() {
        var calls = 0
        val b = bridge(onMediaDetected = { _, _, _, _ -> calls++ })

        b.onMediaFound("wrong-token", "https://cdn.example.com/v.mp4", "T", "video/mp4")

        assertEquals(0, calls)
    }

    @Test
    fun mediaFoundWithToken_isDelivered() {
        var url: String? = null
        val b = bridge(onMediaDetected = { u, _, _, _ -> url = u })

        b.onMediaFound(token, "https://cdn.example.com/v.mp4", "T", "video/mp4")

        assertEquals("https://cdn.example.com/v.mp4", url)
    }

    @Test
    fun blankUrl_isDroppedEvenWithToken() {
        var calls = 0
        val b = bridge(onMediaDetected = { _, _, _, _ -> calls++ })

        b.onMediaFound(token, "", "T", "")

        assertEquals(0, calls)
    }

    @Test
    fun playbackAndPipWithWrongToken_areDropped() {
        var playing: Boolean? = null
        var pip = 0
        val b = bridge(
            onPlaybackStateChanged = { playing = it },
            onPipRequested = { pip++ },
        )

        b.onVideoPlaybackChanged("wrong-token", true)
        b.onEnterPipRequested("wrong-token")

        assertNull(playing)
        assertEquals(0, pip)
    }

    @Test
    fun playbackAndPipWithToken_areDelivered() {
        var playing: Boolean? = null
        var pip = 0
        val b = bridge(
            onPlaybackStateChanged = { playing = it },
            onPipRequested = { pip++ },
        )

        b.onVideoPlaybackChanged(token, true)
        b.onEnterPipRequested(token)

        assertEquals(true, playing)
        assertEquals(1, pip)
    }
}

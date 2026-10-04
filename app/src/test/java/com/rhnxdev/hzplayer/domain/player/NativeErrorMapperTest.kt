package com.rhnxdev.hzplayer.domain.player

import com.rhnxdev.hzplayer.domain.model.PlaybackErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeErrorMapperTest {

    @Test
    fun classNumbers_matchNativeHeader() {
        // Literal values: renumbering either side (NetworkIo.h or Kotlin) must fail here.
        assertEquals(0, NativeErrorClass.NONE)
        assertEquals(1, NativeErrorClass.NETWORK)
        assertEquals(2, NativeErrorClass.TIMEOUT)
        assertEquals(3, NativeErrorClass.AUTH)
        assertEquals(4, NativeErrorClass.NOT_FOUND)
        assertEquals(5, NativeErrorClass.SERVER)
        assertEquals(6, NativeErrorClass.FORMAT)
        assertEquals(8, NativeErrorClass.UNKNOWN)
    }

    @Test
    fun eachClass_mapsToExpectedKindAndString() {
        val expected = mapOf(
            1 to (PlaybackErrorKind.NETWORK to "player_error_network"),
            2 to (PlaybackErrorKind.TIMEOUT to "player_error_timeout"),
            3 to (PlaybackErrorKind.AUTH to "player_error_auth"),
            4 to (PlaybackErrorKind.FILE_NOT_FOUND to "player_error_not_found"),
            5 to (PlaybackErrorKind.NETWORK to "player_error_server"),
            6 to (PlaybackErrorKind.FORMAT_UNSUPPORTED to "player_error_format"),
            8 to (PlaybackErrorKind.UNKNOWN to "player_error_unknown"),
        )
        expected.forEach { (cls, pair) ->
            val mapped = NativeErrorMapper.map(cls)!!
            assertEquals(pair.first, mapped.kind)
            assertEquals(pair.second, mapped.stringResName)
            assertEquals("", mapped.sanitizedDetail)
        }
    }

    @Test
    fun none_isNull_andUnknownNumberIsUnknown() {
        assertNull(NativeErrorMapper.map(0))
        assertEquals(PlaybackErrorKind.UNKNOWN, NativeErrorMapper.map(99)!!.kind)
    }

    @Test
    fun dashMessage_usesDedicatedString() {
        assertEquals("player_error_dash_native", NativeErrorMapper.DASH_UNSUPPORTED.stringResName)
    }

    @Test
    fun isDashManifest_detectsPathAndMime() {
        assertTrue(isDashManifest("https://a.test/v.mpd", null))
        assertTrue(isDashManifest("https://a.test/v.MPD?x=1", null))
        assertTrue(isDashManifest("https://a.test/stream", "application/dash+xml"))
        assertFalse(isDashManifest("https://a.test/v.m3u8", null))
        assertFalse(isDashManifest("https://a.test/v.mp4?f=.mpd", "video/mp4"))
    }
}

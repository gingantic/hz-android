package com.rhnxdev.hzplayer.data.datasource.player

import android.net.Uri
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Guards the SMB subtitle-URI rebuild: the sibling URI must be derived from the
 * original [Uri] via `buildUpon().encodedPath(...)` so that already-encoded segments
 * (e.g. `%20` for spaces) are NOT double-encoded into `%2520`, and the host/port/
 * userInfo from the original are preserved.
 *
 * The old code string-spliced "smb://$credPrefix$host:$port$encodedParentPath/..."`,
 * which collapsed already-encoded parent segments and re-encoded via Uri.parse,
 * producing a URI that didn't match the server's listing → IOException on open.
 */
@RunWith(RobolectricTestRunner::class)
class SmbSubtitleUriTest {

    @Test
    fun rebuild_preservesEncodedSpacesAndCredentials() {
        val androidUri = Uri.parse(
            "smb://user:pass@192.168.1.50:445/Share/Movies/My%20Movie.mkv",
        )
        val encodedParentPath = "/Share/Movies"
        val encodedName = Uri.encode("My Movie.srt") // -> "My%20Movie.srt"

        val result = androidUri.buildUpon()
            .encodedPath("$encodedParentPath/$encodedName")
            .build()

        assertEquals(
            "smb://user:pass@192.168.1.50:445/Share/Movies/My%20Movie.srt",
            result.toString(),
        )
        assertFalse("must not double-encode spaces", result.encodedPath!!.contains("%2520"))
        assertEquals("192.168.1.50", result.host)
        assertEquals(445, result.port)
        assertEquals("user:pass", result.userInfo)
    }

    @Test
    fun rebuild_withCjkShareName_preservesEncoding() {
        val androidUri = Uri.parse(
            "smb://host/Share/dir%20one/【file】.mkv",
        )
        val encodedParentPath = "/Share/dir%20one"
        val encodedName = Uri.encode("【file】.srt")

        val result = androidUri.buildUpon()
            .encodedPath("$encodedParentPath/$encodedName")
            .build()

        // Encoded spaces in the parent path survive (the old substringBeforeLast('/')
        // + string-splice approach collapsed "dir%20one" into "dir one").
        assertEquals(true, result.encodedPath!!.contains("dir%20one"))
        assertFalse("must not double-encode", result.encodedPath!!.contains("%2520"))
    }
}

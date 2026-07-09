package com.rhnxdev.hzplayer.domain.player

import androidx.media3.common.PlaybackException
import com.rhnxdev.hzplayer.domain.model.PlaybackErrorKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

class PlaybackErrorMapperTest {

    @Test
    fun networkErrorCode_mapsToNetworkKind() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            null,
        )
        assertEquals(PlaybackErrorKind.NETWORK, mapped.kind)
        assertEquals("player_error_network", mapped.stringResName)
    }

    @Test
    fun timeoutErrorCode_mapsToTimeoutKind() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            null,
        )
        assertEquals(PlaybackErrorKind.TIMEOUT, mapped.kind)
    }

    @Test
    fun authCause_stripsCredentialsAndMapsToAuth() {
        val cause = Exception(
            "Auth failed for smb://bob:secret@192.168.1.50/Movies/foo.mkv",
        )
        val mapped = PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_UNSPECIFIED, cause)
        assertEquals(PlaybackErrorKind.AUTH, mapped.kind)
        assertFalse("credentials must not leak", mapped.sanitizedDetail.contains("secret"))
        assertFalse("hostname must be masked", mapped.sanitizedDetail.contains("192.168.1.50"))
        assertFalse("username must not leak", mapped.sanitizedDetail.contains("bob"))
    }

    @Test
    fun fileNotFoundErrorCode_mapsToNotFound() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            null,
        )
        assertEquals(PlaybackErrorKind.FILE_NOT_FOUND, mapped.kind)
    }

    @Test
    fun drmErrorCode_mapsToDrm() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            null,
        )
        assertEquals(PlaybackErrorKind.DRM, mapped.kind)
    }

    @Test
    fun decoderErrorCode_mapsToDecoder() {
        val mapped = PlaybackErrorMapper.map(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            null,
        )
        assertEquals(PlaybackErrorKind.DECODER, mapped.kind)
    }

    @Test
    fun unknownErrorCode_mapsToUnknown() {
        val mapped = PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_UNSPECIFIED, null)
        assertEquals(PlaybackErrorKind.UNKNOWN, mapped.kind)
        assertEquals("player_error_unknown", mapped.stringResName)
    }

    @Test
    fun ftpCredentialUri_isSanitized() {
        val cause = Exception(
            "Login failed for ftp://user:passw0rd@server.example.com:21/video.mkv",
        )
        val mapped = PlaybackErrorMapper.map(PlaybackException.ERROR_CODE_UNSPECIFIED, cause)
        assertFalse(mapped.sanitizedDetail.contains("passw0rd"))
        assertFalse(mapped.sanitizedDetail.contains("user:"))
        assertFalse(mapped.sanitizedDetail.contains("server.example.com"))
    }
}

package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Unit tests for the video/audio detection helpers used to route extensionless
 * stream URLs (e.g. bucket URLs) to the correct player surface.
 *
 * [probeContentType] / [isVideoStreamUrl] perform network IO and are intentionally
 * not covered here; [isVideoContentType] and [isVideoOrStreamDefault] are pure.
 */
class MediaExtensionsTest {

    @Test
    fun isVideoContentType_videoMp4_isVideo() {
        assertTrue(isVideoContentType("video/mp4"))
    }

    @Test
    fun isVideoContentType_videoWithCharsetParam_isVideo() {
        assertTrue(isVideoContentType("video/webm; charset=utf-8"))
    }

    @Test
    fun isVideoContentType_audioMpeg_isAudio() {
        assertFalse(isVideoContentType("audio/mpeg"))
    }

    @Test
    fun isVideoContentType_null_defaultsToVideo() {
        assertTrue(isVideoContentType(null))
    }

    @Test
    fun isVideoContentType_octetStream_defaultsToVideo() {
        assertTrue(isVideoContentType("application/octet-stream"))
    }

    @Test
    fun isVideoContentType_unknownDefaultStream_defaultsToVideo() {
        assertTrue(isVideoContentType("application/vnd.apple.mpegurl"))
    }

    @Test
    fun isVideoOrStreamDefault_recognizedVideoExtension_isVideo() {
        assertTrue(isVideoOrStreamDefault("https://example.com/clip.mp4"))
    }

    @Test
    fun isVideoOrStreamDefault_recognizedAudioExtension_isAudio() {
        assertFalse(isVideoOrStreamDefault("https://example.com/song.mp3"))
    }

    @Test
    fun isVideoOrStreamDefault_extensionlessBucketUrl_defaultsToVideo() {
        assertTrue(isVideoOrStreamDefault("https://bucket.s3.amazonaws.com/v/abc123"))
    }

    @Test
    fun isVideoExtension_bucketUrlWithoutExtension_isFalse() {
        assertFalse(isVideoExtension("https://bucket.s3.amazonaws.com/v/abc123"))
    }
}

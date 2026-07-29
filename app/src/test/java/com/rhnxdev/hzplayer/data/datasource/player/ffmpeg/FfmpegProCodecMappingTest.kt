package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg

import androidx.media3.common.MimeTypes
import com.rhnxdev.hzplayer.data.datasource.player.mp4fork.BoxParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards the HZPLAYER-PATCH in the vendored [BoxParser]: QuickTime pro-codec
 * sample entries must map to the MIME types the FFmpeg renderers claim, and
 * [FfmpegLibrary] must resolve those MIME types to the right FFmpeg decoders.
 */
class FfmpegProCodecMappingTest {

    private fun fourcc(code: String): Int {
        require(code.length == 4)
        return code.fold(0) { acc, c -> (acc shl 8) or c.code }
    }

    @Test
    fun `all prores sample entries map to prores mime`() {
        for (code in listOf("apch", "apcn", "apcs", "apco", "ap4h", "ap4x")) {
            assertEquals(
                "fourcc $code",
                FfmpegMimeTypes.VIDEO_PRORES,
                BoxParser.proCodecMimeTypeForAtom(fourcc(code)),
            )
        }
    }

    @Test
    fun `dnxhd mjpeg and cineform sample entries map to their mimes`() {
        assertEquals(FfmpegMimeTypes.VIDEO_DNXHD, BoxParser.proCodecMimeTypeForAtom(fourcc("AVdn")))
        for (code in listOf("jpeg", "mjpa", "mjpb")) {
            assertEquals(
                "fourcc $code",
                MimeTypes.VIDEO_MJPEG,
                BoxParser.proCodecMimeTypeForAtom(fourcc(code)),
            )
        }
        assertEquals(
            FfmpegMimeTypes.VIDEO_CINEFORM,
            BoxParser.proCodecMimeTypeForAtom(fourcc("CFHD")),
        )
    }

    @Test
    fun `standard sample entries are not claimed by the patch`() {
        // Codecs Media3 already handles must keep their stock parsing path.
        for (code in listOf("avc1", "hvc1", "hev1", "vp09", "av01", "mp4v")) {
            assertNull("fourcc $code", BoxParser.proCodecMimeTypeForAtom(fourcc(code)))
        }
    }

    @Test
    fun `ffmpeg codec names resolve for every claimed video mime`() {
        // getCodecName is the static half of supportsFormat (the native
        // availability probe is device-only); it must know every MIME the
        // renderers whitelist.
        assertEquals("prores", FfmpegLibrary.getCodecName(FfmpegMimeTypes.VIDEO_PRORES))
        assertEquals("dnxhd", FfmpegLibrary.getCodecName(FfmpegMimeTypes.VIDEO_DNXHD))
        assertEquals("cfhd", FfmpegLibrary.getCodecName(FfmpegMimeTypes.VIDEO_CINEFORM))
        assertEquals("mjpeg", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_MJPEG))
        assertEquals("mpeg2video", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_MPEG2))
        assertEquals("mpeg4", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_MP4V))
        assertEquals("vc1", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_VC1))
        assertEquals("h264", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_H264))
        assertEquals("hevc", FfmpegLibrary.getCodecName(MimeTypes.VIDEO_H265))
        // Audio fallbacks the plan calls out explicitly.
        assertEquals("alac", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_ALAC))
        assertEquals("dca", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_DTS))
        assertEquals("truehd", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_TRUEHD))
        assertEquals("eac3", FfmpegLibrary.getCodecName(MimeTypes.AUDIO_E_AC3))
        // Unknown MIME types must not be claimed.
        assertNull(FfmpegLibrary.getCodecName(MimeTypes.VIDEO_DOLBY_VISION))
        assertNull(FfmpegLibrary.getCodecName("video/unknown"))
    }
}

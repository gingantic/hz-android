package com.rhnxdev.hzplayer.data.datasource.player.ffmpeg

/**
 * Sample MIME types for QuickTime pro codecs that Media3 has no constants for.
 * Emitted by the patched [com.rhnxdev.hzplayer.data.datasource.player.mp4fork.BoxParser]
 * and claimed by the FFmpeg software renderers — MediaCodec never handles these.
 */
object FfmpegMimeTypes {
    const val VIDEO_PRORES = "video/prores"
    const val VIDEO_DNXHD = "video/dnxhd"
    const val VIDEO_CINEFORM = "video/cfhd"
}

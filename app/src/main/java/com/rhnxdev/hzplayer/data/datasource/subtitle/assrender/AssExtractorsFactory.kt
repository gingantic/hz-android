package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import android.net.Uri
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.DefaultExtractorsFactory
import androidx.media3.extractor.Extractor
import androidx.media3.extractor.ExtractorsFactory
import androidx.media3.extractor.mkv.MatroskaExtractor
import androidx.media3.extractor.mp4.Mp4Extractor
import com.rhnxdev.hzplayer.data.datasource.player.mp4fork.HzMp4Extractor

/**
 * An [ExtractorsFactory] that:
 * 1. Sets [AssSubtitleParserFactory] so ExoPlayer doesn't crash on ASS tracks
 * 2. Enables constant-bitrate seeking for headerless audio formats (MP3/AAC/AMR)
 * 3. Replaces MatroskaExtractor with [AssMatroskaExtractor] to eavesdrop
 *    on raw ASS data for libass rendering
 * 4. Replaces Mp4Extractor with the vendored [HzMp4Extractor], which also
 *    emits QuickTime pro-codec tracks (ProRes/DNxHD/MJPEG/CineForm) for the
 *    FFmpeg software renderers instead of silently dropping them
 */
@UnstableApi
class AssExtractorsFactory(
    private val handler: AssHandler,
) : ExtractorsFactory {

    private val defaultFactory = DefaultExtractorsFactory()
        .setSubtitleParserFactory(AssSubtitleParserFactory())
        .setConstantBitrateSeekingEnabled(true)
        .setConstantBitrateSeekingAlwaysEnabled(true)

    override fun createExtractors(): Array<Extractor> =
        createExtractors(Uri.EMPTY, emptyMap())

    override fun createExtractors(
        uri: Uri,
        responseHeaders: Map<String, List<String>>,
    ): Array<Extractor> {
        val defaults = defaultFactory.createExtractors(uri, responseHeaders)

        return defaults.map { extractor ->
            when {
                extractor is MatroskaExtractor ->
                    AssMatroskaExtractor(handler)
                extractor is Mp4Extractor ->
                    HzMp4Extractor(AssSubtitleParserFactory())
                else -> extractor
            }
        }.toTypedArray()
    }
}

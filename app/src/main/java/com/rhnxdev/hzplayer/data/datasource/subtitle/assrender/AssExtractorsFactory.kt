package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

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
 * 2. Replaces MatroskaExtractor with [AssMatroskaExtractor] to eavesdrop
 *    on raw ASS data for libass rendering
 * 3. Replaces Mp4Extractor with the vendored [HzMp4Extractor], which also
 *    emits QuickTime pro-codec tracks (ProRes/DNxHD/MJPEG/CineForm) for the
 *    FFmpeg software renderers instead of silently dropping them
 */
@UnstableApi
class AssExtractorsFactory(
    private val handler: AssHandler,
) : ExtractorsFactory {

    private val defaultFactory = DefaultExtractorsFactory()
        .setSubtitleParserFactory(AssSubtitleParserFactory())

    override fun createExtractors(): Array<Extractor> {
        val defaults = defaultFactory.createExtractors()

        return defaults.map { extractor ->
            when {
                extractor is MatroskaExtractor && extractor !is AssMatroskaExtractor ->
                    AssMatroskaExtractor(handler)
                extractor is Mp4Extractor ->
                    HzMp4Extractor(AssSubtitleParserFactory())
                else -> extractor
            }
        }.toTypedArray()
    }
}

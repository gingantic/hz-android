package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.Format
import androidx.media3.common.util.Consumer
import androidx.media3.common.util.UnstableApi
import androidx.media3.extractor.text.CuesWithTiming
import androidx.media3.extractor.text.DefaultSubtitleParserFactory
import androidx.media3.extractor.text.SubtitleParser
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isLibassSubtitleFormat

/**
 * A [SubtitleParser.Factory] that returns a no-op parser for ASS/SSA tracks,
 * preventing ExoPlayer from trying to parse them (which would crash with
 * "Legacy decoding is disabled"). All other formats are handled normally.
 */
@UnstableApi
class AssSubtitleParserFactory : SubtitleParser.Factory {

    private val defaultFactory = DefaultSubtitleParserFactory()

    override fun supportsFormat(format: Format): Boolean {
        if (isLibassSubtitleFormat(format)) {
            return true
        }
        return defaultFactory.supportsFormat(format)
    }

    override fun getCueReplacementBehavior(format: Format): Int {
        if (isLibassSubtitleFormat(format)) {
            return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
        }
        return defaultFactory.getCueReplacementBehavior(format)
    }

    override fun create(format: Format): SubtitleParser {
        if (isLibassSubtitleFormat(format)) {
            return AssNoOpSubtitleParser()
        }
        return defaultFactory.create(format)
    }
}

/**
 * A subtitle parser that does nothing — satisfies ExoPlayer's pipeline
 * without actually processing ASS data (our libass handles that).
 */
@UnstableApi
internal class AssNoOpSubtitleParser : SubtitleParser {

    override fun parse(
        data: ByteArray,
        offset: Int,
        length: Int,
        outputOptions: SubtitleParser.OutputOptions,
        output: Consumer<CuesWithTiming>,
    ) {
        // No-op: libass handles ASS rendering via AssHandler
    }

    override fun getCueReplacementBehavior(): Int {
        return Format.CUE_REPLACEMENT_BEHAVIOR_REPLACE
    }
}

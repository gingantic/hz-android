package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Guards subtitle language resolution: bare names, ISO codes, engine label
 * prefixes, qualifiers, region-tagged codes and release-style filenames must
 * all resolve to the right display label, flag country and sort key.
 */
class SubtitleLanguageResolverTest {

    private fun assertResolves(
        raw: String,
        display: String,
        flag: String?,
        sortKey: String = display.lowercase(),
    ) {
        val resolved = SubtitleLanguageResolver.resolve(raw)
        assertEquals("display for '$raw'", display, resolved.displayName)
        assertEquals("flag for '$raw'", flag, resolved.countryCode)
        assertEquals("sortKey for '$raw'", sortKey, resolved.sortKey)
    }

    @Test
    fun plainNamesResolve() {
        assertResolves("English", "English", "GB")
        assertResolves("Japanese", "Japanese", "JP")
        assertResolves("français", "français", "FR", "french")
    }

    @Test
    fun isoCodesExpandToCanonicalNames() {
        assertResolves("eng", "English", "GB", "english")
        assertResolves("jpn", "Japanese", "JP", "japanese")
        assertResolves("fra", "French", "FR", "french")
        assertResolves("zh", "Chinese", "CN", "chinese")
    }

    @Test
    fun engineLabelPrefixesAreStripped() {
        assertResolves("Subtitle - eng", "English", "GB", "english")
        assertResolves("Subtitles: Korean", "Korean", "KR", "korean")
    }

    @Test
    fun qualifiersRefineTheFlag() {
        assertResolves("English (SDH)", "English (SDH)", "GB", "english")
        assertResolves("Chinese (Simplified)", "Chinese (Simplified)", "CN", "chinese")
        assertResolves("Chinese (Traditional)", "Chinese (Traditional)", "TW", "chinese")
        assertResolves("Portuguese (Brazil)", "Portuguese (Brazil)", "BR", "portuguese")
        assertResolves("eng (forced)", "English (forced)", "GB", "english")
    }

    @Test
    fun regionTaggedCodesResolve() {
        assertResolves("movie.zh-CN", "Chinese", "CN", "chinese")
        assertResolves("movie.zh-TW", "Chinese", "TW", "chinese")
        assertResolves("film.pt.br", "Portuguese", "BR", "portuguese")
        assertResolves("show.es-419", "Spanish", "MX", "spanish")
    }

    @Test
    fun releaseStyleFilenamesResolveButKeepTheirLabel() {
        assertResolves(
            "Movie.2023.1080p.WEB-DL.English.srt",
            "Movie.2023.1080p.WEB-DL.English",
            "GB",
            "english",
        )
        assertResolves(
            "Breaking.Bad.S01E01.720p.BluRay.x264.en.cc.srt",
            "Breaking.Bad.S01E01.720p.BluRay.x264.en.cc",
            "GB",
            "english",
        )
    }

    @Test
    fun nativeNamesKeepTheirLabelButSortByEnglishName() {
        assertResolves("日本語", "日本語", "JP", "japanese")
        assertResolves("русский", "русский", "RU", "russian")
        assertResolves("العربية", "العربية", "SA", "arabic")
    }

    @Test
    fun unknownNamesFallBackGracefully() {
        val resolved = SubtitleLanguageResolver.resolve("Klingon")
        assertEquals("Klingon", resolved.displayName)
        assertNull(resolved.countryCode)
        assertEquals("klingon", resolved.sortKey)

        val und = SubtitleLanguageResolver.resolve("und")
        assertNull(und.countryCode)
    }
}

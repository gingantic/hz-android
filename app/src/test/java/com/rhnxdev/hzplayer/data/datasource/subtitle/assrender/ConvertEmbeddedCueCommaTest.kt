package com.rhnxdev.hzplayer.data.datasource.subtitle.assrender

import androidx.media3.common.util.UnstableApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Test

/**
 * Guards [SubtitleConverters.convertEmbeddedCue]: the libass pipeline must
 * NOT inject a comma at the start of the dialogue text. A leading comma
 * (the symptom reported: ",Subject to change.") would come from an
 * Effect/Text field-merge bug, not from source punctuation.
 *
 * The converter receives the raw SRT cue bytes (timing line intact) and must
 * emit a valid MKV Dialogue line whose Text field begins with the same first
 * character as the source text — never a comma unless the source has one.
 */
@UnstableApi
class ConvertEmbeddedCueCommaTest {

    private fun textOf(rawCue: String): String {
        val bytes = rawCue.toByteArray(Charsets.UTF_8)
        val out = SubtitleConverters.convertEmbeddedCue(bytes, isVtt = false)
        assertNotNull("convertEmbeddedCue returned null for: $rawCue", out)
        // emitted line: "Dialogue: 0,<dur>,0,0,HzDefault,,0,0,0,,<text>"
        // Text is the 9th comma-separated field (index 10); it may itself
        // contain commas, so rejoin the tail instead of substringAfterLast.
        val line = String(out!!, Charsets.UTF_8).removePrefix("Dialogue:").trimStart()
        val f = line.split(",", limit = 11)
        return f[10]
    }

    @Test
    fun cue_with_dash_speaker_keeps_dash_not_comma() {
        // Exact shape from the bug report: "-Subject to change." / "-What?"
        val raw = "1\n00:00:00,000 --> 00:00:01,410\n-Subject to change.\n-What?\n"
        val text = textOf(raw)
        assertEquals("-Subject to change.\\N-What?", text)
        assertFalse("text must not start with a comma", text.startsWith(","))
        assertEquals('-', text.first())
    }

    @Test
    fun cue_with_source_leading_comma_is_preserved_not_injected() {
        // If the SOURCE truly starts with a comma, it stays (faithful render).
        // This asserts we don't ADD one on top of it.
        val raw = "1\n00:00:00,000 --> 00:00:01,000\n,Leading comma line\n"
        val text = textOf(raw)
        assertEquals(",Leading comma line", text)
        // exactly one leading comma == source's own, not two
        assertFalse(text.startsWith(",,"))
    }

    @Test
    fun cue_with_internal_comma_is_preserved() {
        // "-Right, okay." — comma is mid-sentence source punctuation.
        val raw = "1\n00:00:00,000 --> 00:00:02,042\n-Right, okay. I've got--\n-JUDD: Good.\n"
        val text = textOf(raw)
        assertEquals("-Right, okay. I've got--\\N-JUDD: Good.", text)
        assertFalse("text must not start with a comma", text.startsWith(","))
    }

    @Test
    fun cue_without_timing_line_still_emits_text() {
        // ExoPlayer sometimes strips the `-->` line; converter must not drop it.
        val raw = "-Subject to change.\n-What?\n"
        val text = textOf(raw)
        assertEquals("-Subject to change.\\N-What?", text)
        assertFalse(text.startsWith(","))
    }

    @Test
    fun cue_with_html_tags_maps_to_ass_overrides() {
        val raw = "1\n00:00:00,000 --> 00:00:01,000\n<i>Italic</i> and <b>Bold</b> and <u>Underline</u>\n"
        val text = textOf(raw)
        assertEquals("{\\i1}Italic{\\i0} and {\\b1}Bold{\\b0} and {\\u1}Underline{\\u0}", text)
    }
}

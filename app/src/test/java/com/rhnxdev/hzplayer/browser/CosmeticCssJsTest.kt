package com.rhnxdev.hzplayer.browser

import org.json.JSONObject
import org.json.JSONTokener
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A3 regression: the cosmetic CSS is embedded with [JSONObject.quote] and applied
 * via textContent — the old hand-rolled escaping broke on any backslash.
 * Robolectric runs the real AOSP org.json, the same implementation as on device.
 */
@RunWith(RobolectricTestRunner::class)
class CosmeticCssJsTest {

    @Test
    fun css_isEmbeddedAsJsonQuotedLiteral() {
        val css = ".ad-banner { display: none !important; }"
        val js = TabManager.cosmeticCssJs(css)
        assertTrue(js.contains(JSONObject.quote(css)))
        assertTrue(js.contains("style.textContent"))
        assertFalse(js.contains("innerHTML"))
    }

    @Test
    fun backslashQuoteAndNewline_surviveRoundTrip() {
        // Element-picker rules and filter lists both feed this string; either
        // can contain backslashes and quotes.
        val css = "div::before { content: '\\2014'; }\n.x[title=\"a\\\\b\"] { color: red }"
        val js = TabManager.cosmeticCssJs(css)
        val literal = JSONObject.quote(css)
        assertTrue(js.contains(literal))
        assertFalse(literal.contains("\n"))
        assertEquals(css, JSONTokener(literal).nextValue())
    }
}

package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Guards the header rules shared with ExoPlayer plus the native-only injection checks. */
@RunWith(RobolectricTestRunner::class)
class HttpHeaderSanitizerTest {

    @Test
    fun forbiddenHeaders_areDropped() {
        val out = sanitizeHttpRequestHeaders(
            mapOf(
                "Range" to "bytes=0-", "Host" to "x", "Connection" to "close",
                "Accept-Encoding" to "gzip", "Sec-Fetch-Mode" to "cors", "Cookie" to "a=b",
            ),
        )
        assertEquals(setOf("Cookie", "User-Agent"), out.keys)
    }

    @Test
    fun casing_isNormalized() {
        val out = sanitizeHttpRequestHeaders(mapOf("referer" to "https://a.test/p", "user-agent" to "UA"))
        assertEquals("https://a.test/p", out["Referer"])
        assertEquals("UA", out["User-Agent"])
    }

    @Test
    fun origin_isDerivedFromRefererIncludingPort() {
        val out = sanitizeHttpRequestHeaders(mapOf("Referer" to "http://a.test:8080/page?x=1"))
        assertEquals("http://a.test:8080", out["Origin"])
    }

    @Test
    fun userAgent_defaultAddedAndSuppliedKept() {
        assertEquals(DEFAULT_WEB_USER_AGENT, sanitizeHttpRequestHeaders(emptyMap())["User-Agent"])
        assertEquals("Mine", sanitizeHttpRequestHeaders(mapOf("User-Agent" to "Mine"))["User-Agent"])
    }

    @Test
    fun crlfValue_isDropped() {
        val out = sanitizeHttpRequestHeaders(mapOf("X-Injected" to "a\r\nEvil: 1", "X-Ok" to "fine"))
        assertFalse(out.containsKey("X-Injected"))
        assertTrue(out.containsKey("X-Ok"))
    }

    @Test
    fun invalidTokenKey_isDropped() {
        val out = sanitizeHttpRequestHeaders(mapOf("Bad Key" to "v", "Bad:Key" to "v"))
        assertEquals(setOf("User-Agent"), out.keys)
    }

    @Test
    fun blankKeyOrValue_isDropped() {
        val out = sanitizeHttpRequestHeaders(mapOf(" " to "v", "X-A" to " "))
        assertEquals(setOf("User-Agent"), out.keys)
    }
}

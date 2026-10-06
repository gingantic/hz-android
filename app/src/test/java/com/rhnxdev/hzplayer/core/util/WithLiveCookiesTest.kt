package com.rhnxdev.hzplayer.core.util

import android.webkit.CookieManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * A4 regression: live cookies must resolve for the media/target URL, never the
 * page URL — attaching page-session cookies to a third-party media host leaks them.
 * Robolectric's CookieManager is a real implementation with domain matching.
 */
@RunWith(RobolectricTestRunner::class)
class WithLiveCookiesTest {

    private val pageUrl = "https://page.example.com/article"
    private val mediaUrl = "https://cdn.example.com/video/master.m3u8"

    @Test
    fun cookieForTargetUrl_isAttached() {
        CookieManager.getInstance().setCookie(mediaUrl, "cdn_auth=xyz")

        val headers = emptyMap<String, String>().withLiveCookies(targetUrl = mediaUrl)

        assertEquals("cdn_auth=xyz", headers["Cookie"])
    }

    @Test
    fun pageCookie_isNotAttachedToThirdPartyMediaHost() {
        CookieManager.getInstance().setCookie(pageUrl, "page_session=secret")

        val headers = emptyMap<String, String>()
            .withLiveCookies(targetUrl = mediaUrl, refererUrl = pageUrl)

        assertFalse(headers.containsKey("Cookie"))
    }

    @Test
    fun existingCookieKey_winsAnyCasing() {
        CookieManager.getInstance().setCookie(mediaUrl, "cdn_auth=xyz")

        val headers = mapOf("cookie" to "manual=1").withLiveCookies(targetUrl = mediaUrl)

        assertEquals("manual=1", headers["cookie"])
        assertFalse(headers.containsKey("Cookie"))
    }

    @Test
    fun nonHttpTarget_skipsCookieLookupAndKeepsReferer() {
        CookieManager.getInstance().setCookie(pageUrl, "page_session=secret")

        val headers = emptyMap<String, String>()
            .withLiveCookies(targetUrl = "magnet:?xt=urn:btih:abc", refererUrl = pageUrl)

        assertFalse(headers.containsKey("Cookie"))
        assertEquals(pageUrl, headers["Referer"])
    }

    @Test
    fun existingReferer_wins() {
        val headers = mapOf("Referer" to "https://other.test/").withLiveCookies(
            targetUrl = mediaUrl,
            refererUrl = pageUrl,
        )

        assertEquals("https://other.test/", headers["Referer"])
    }
}

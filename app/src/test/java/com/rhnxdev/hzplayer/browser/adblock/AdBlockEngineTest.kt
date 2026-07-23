package com.rhnxdev.hzplayer.browser.adblock

import com.rhnxdev.hzplayer.browser.BrowserSettings
import org.junit.Assert.assertFalse
import org.junit.Test

class AdBlockEngineTest {

    @Test
    fun testAdBlockDisabledReturnsFalse() {
        val settings = BrowserSettings(adBlockEnabled = false)
        val blocked = AdBlockEngine.shouldBlockRequest(
            requestUrl = "https://doubleclick.net/ad.js",
            pageUrl = "https://example.com",
            settings = settings
        )
        assertFalse(blocked)
    }

    @Test
    fun testDataAndBlobUrlsAreNotBlocked() {
        val settings = BrowserSettings(adBlockEnabled = true)
        val dataBlocked = AdBlockEngine.shouldBlockRequest(
            requestUrl = "data:image/png;base64,iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mNk+M9QDwADhgGAWjR9awAAAABJRU5ErkJggg==",
            pageUrl = "https://example.com",
            settings = settings
        )
        assertFalse(dataBlocked)

        val blobBlocked = AdBlockEngine.shouldBlockRequest(
            requestUrl = "blob:https://example.com/1234-5678",
            pageUrl = "https://example.com",
            settings = settings
        )
        assertFalse(blobBlocked)
    }
}

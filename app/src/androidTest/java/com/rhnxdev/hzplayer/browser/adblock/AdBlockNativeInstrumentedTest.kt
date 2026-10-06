package com.rhnxdev.hzplayer.browser.adblock

import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises the adblock-rust JNI surface on a device.
 *
 * Robolectric cannot cover this: the engine is a real `.so` loaded per-ABI, and
 * these tests pin the handle contract the Kotlin layer relies on — opaque
 * handles, `false` for unknown/zero handles, idempotent destroy.
 */
@RunWith(AndroidJUnit4::class)
class AdBlockNativeInstrumentedTest {

    private val pageUrl = "https://example.com/"

    @Test
    fun nativeLibraryIsLoaded() {
        assertTrue("libadblock_jni.so failed to load", AdBlockNative.isLibraryLoaded)
    }

    @Test
    fun networkRuleBlocksMatchingRequestOnly() {
        val handle = AdBlockNative.nativeCreateEngine(arrayOf("||ads.example.com^"))
        assertNotEquals("engine creation failed", 0L, handle)
        try {
            assertTrue(
                "matching request must be blocked",
                AdBlockNative.nativeShouldBlock(
                    handle, "https://ads.example.com/banner.js", pageUrl, "script"
                )
            )
            assertFalse(
                "clean request must not be blocked",
                AdBlockNative.nativeShouldBlock(
                    handle, "https://example.com/app.js", pageUrl, "script"
                )
            )
        } finally {
            AdBlockNative.nativeDestroyEngine(handle)
        }
    }

    @Test
    fun exceptionRuleOverridesBlockRule() {
        val handle = AdBlockNative.nativeCreateEngine(
            arrayOf("||example.com^", "@@||example.com/allowed.js")
        )
        assertNotEquals(0L, handle)
        try {
            assertFalse(
                "exception rule must win",
                AdBlockNative.nativeShouldBlock(
                    handle, "https://example.com/allowed.js", pageUrl, "script"
                )
            )
            assertTrue(
                "other requests must still be blocked",
                AdBlockNative.nativeShouldBlock(
                    handle, "https://example.com/other.js", pageUrl, "script"
                )
            )
        } finally {
            AdBlockNative.nativeDestroyEngine(handle)
        }
    }

    @Test
    fun destroyStopsBlockingAndIsIdempotent() {
        val handle = AdBlockNative.nativeCreateEngine(arrayOf("||ads.example.com^"))
        assertNotEquals(0L, handle)

        assertTrue(
            AdBlockNative.nativeShouldBlock(
                handle, "https://ads.example.com/a.js", pageUrl, "script"
            )
        )

        AdBlockNative.nativeDestroyEngine(handle)
        assertFalse(
            "destroyed handle must not resolve",
            AdBlockNative.nativeShouldBlock(
                handle, "https://ads.example.com/a.js", pageUrl, "script"
            )
        )
        // Must not crash or corrupt the registry.
        AdBlockNative.nativeDestroyEngine(handle)
    }

    @Test
    fun zeroAndUnknownHandlesAreSafe() {
        assertFalse(
            AdBlockNative.nativeShouldBlock(
                0L, "https://ads.example.com/a.js", pageUrl, "script"
            )
        )
        assertFalse(
            AdBlockNative.nativeShouldBlock(
                Long.MAX_VALUE, "https://ads.example.com/a.js", pageUrl, "script"
            )
        )
        // Native returns a null jstring for unknown handles; the Kotlin declaration
        // is non-null, so callers (AdBlockEngine) guard with `?: ""`.
        assertTrue(AdBlockNative.nativeGetCosmeticCss(0L, pageUrl).isNullOrEmpty())
    }

    @Test
    fun cosmeticHideRuleProducesCss() {
        val handle = AdBlockNative.nativeCreateEngine(arrayOf("example.com##.ad-banner"))
        assertNotEquals(0L, handle)
        try {
            val css = AdBlockNative.nativeGetCosmeticCss(handle, pageUrl)
            assertTrue("selector missing from: $css", css.contains(".ad-banner"))
            assertTrue("hide declaration missing from: $css", css.contains("display: none"))
        } finally {
            AdBlockNative.nativeDestroyEngine(handle)
        }
    }

    @Test
    fun cosmeticCssIsEmptyWhenNoRuleMatchesPage() {
        val handle = AdBlockNative.nativeCreateEngine(arrayOf("other-site.example##.ad-banner"))
        assertNotEquals(0L, handle)
        try {
            assertEquals("", AdBlockNative.nativeGetCosmeticCss(handle, pageUrl))
        } finally {
            AdBlockNative.nativeDestroyEngine(handle)
        }
    }
}

package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import android.util.Log
import com.rhnxdev.hzplayer.browser.BrowserSettings
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance thread-safe AdBlock engine strictly dependent on Brave's native adblock-rust via JNI.
 */
object AdBlockEngine {

    private const val TAG = "AdBlockEngine"

    private val nativeEngineHandle = AtomicLong(0L)

    val blockedCount = AtomicLong(0)
    @Volatile var totalRuleCount: Int = 0
        private set

    val isAvailable: Boolean
        get() = AdBlockNative.isLibraryLoaded

    // Never block CAPTCHA / bot-challenge providers. The filter lists (notably
    // EasyPrivacy and hosts lists) occasionally catch their scripts or frames,
    // and uBlock's unbreak exception list isn't loaded — a blocked challenge
    // leaves the site stuck on a blank captcha. Mirrors uBlock's unbreak rules.
    private val CAPTCHA_HOSTS = arrayOf(
        "challenges.cloudflare.com",
        "turnstile.com",
        "hcaptcha.com",
        "recaptcha.net",
        "arkoselabs.com",
        "funcaptcha.com",
        "geetest.com",
        "perimeterx.net",
        "px-cdn.net",
        "datadome.co",
    )

    private fun isCaptchaRequest(requestUrl: String): Boolean {
        val host = try {
            android.net.Uri.parse(requestUrl).host?.lowercase()
        } catch (_: Exception) { null } ?: return false
        if (CAPTCHA_HOSTS.any { host == it || host.endsWith(".$it") }) return true
        // reCAPTCHA is served from google.com / gstatic.com under /recaptcha/
        if ((host.endsWith("google.com") || host.endsWith("gstatic.com")) &&
            requestUrl.contains("/recaptcha/")
        ) return true
        return false
    }

    /**
     * Released once the first [initialize] finishes. [shouldBlockRequest] runs on
     * WebView's network threads, so it can wait here while the engine builds off
     * the main thread — the first page's requests are filtered, not let through.
     */
    private val initLatch = CountDownLatch(1)

    /** Upper bound on that wait; past it requests go unfiltered rather than stall the page. */
    private const val INIT_WAIT_MS = 3000L

    /**
     * Builds the engine for this process. Heavy (reads and parses every list),
     * so call it off the main thread. A no-op when an engine already exists:
     * later changes arrive through [reload].
     */
    fun initialize(context: Context, settings: BrowserSettings) {
        try {
            if (nativeEngineHandle.get() == 0L) reload(context, settings)
        } finally {
            initLatch.countDown()
        }
    }

    @Synchronized
    fun reload(context: Context, settings: BrowserSettings) {
        if (!isAvailable || !settings.adBlockEnabled) {
            destroyQuietly(nativeEngineHandle.getAndSet(0L))
            totalRuleCount = 0
            return
        }

        val filterContents = AdBlockListManager.readActiveFilterContents(
            context = context,
            enabledListIds = settings.enabledFilterLists,
            customRules = settings.customAdBlockRules,
        )

        val newPtr = if (filterContents.isEmpty()) {
            0L
        } else {
            try {
                AdBlockNative.nativeCreateEngine(filterContents.toTypedArray())
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize native adblock engine: ${e.message}")
                0L
            }
        }

        // Build-then-swap: the live engine keeps serving request checks until the
        // replacement exists, and is released only after the handle is replaced.
        // A failed build with rules present keeps the previous engine.
        if (newPtr == 0L && filterContents.isNotEmpty()) return

        destroyQuietly(nativeEngineHandle.getAndSet(newPtr))
        totalRuleCount = if (newPtr != 0L) {
            filterContents.sumOf { content ->
                content.lineSequence().count { line ->
                    line.isNotBlank() && !line.startsWith("!") && !line.startsWith("[")
                }
            }
        } else {
            0
        }
        if (newPtr != 0L) {
            Log.i(TAG, "Native adblock-rust engine loaded ($totalRuleCount estimated rules)")
        }
    }

    private fun destroyQuietly(handle: Long) {
        if (handle == 0L || !AdBlockNative.isLibraryLoaded) return
        try {
            AdBlockNative.nativeDestroyEngine(handle)
        } catch (e: Throwable) {
            Log.e(TAG, "Error destroying native engine: ${e.message}")
        }
    }

    /**
     * Checks if a network request URL should be blocked using the native adblock-rust engine.
     */
    fun shouldBlockRequest(
        requestUrl: String,
        pageUrl: String = "",
        settings: BrowserSettings,
        resourceType: String = "other",
    ): Boolean {
        if (!isAvailable || !settings.adBlockEnabled || requestUrl.isBlank()) return false
        if (requestUrl.startsWith("data:") || requestUrl.startsWith("blob:") || requestUrl.startsWith("file:")) return false

        if (initLatch.count > 0L) {
            try {
                initLatch.await(INIT_WAIT_MS, TimeUnit.MILLISECONDS)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
            }
        }

        val handle = nativeEngineHandle.get()
        if (handle != 0L) {
            try {
                val blocked = AdBlockNative.nativeShouldBlock(
                    engineHandle = handle,
                    requestUrl = requestUrl,
                    pageUrl = pageUrl,
                    resourceType = resourceType
                )
                // Checked only for would-be-blocked requests: Uri parsing on every
                // sub-resource was the dominant cost of this method.
                if (blocked && !isCaptchaRequest(requestUrl)) {
                    blockedCount.incrementAndGet()
                    return true
                }
                return false
            } catch (e: Throwable) {
                Log.e(TAG, "Native shouldBlock error: ${e.message}")
            }
        }

        return false
    }

    /**
     * Generates cosmetic element hiding CSS rules for a given web page URL using native adblock-rust.
     */
    fun getCosmeticCss(pageUrl: String, settings: BrowserSettings): String {
        if (!isAvailable || !settings.adBlockEnabled || !settings.cosmeticFilteringEnabled || pageUrl.isBlank()) {
            return ""
        }

        val handle = nativeEngineHandle.get()
        if (handle != 0L) {
            try {
                return AdBlockNative.nativeGetCosmeticCss(handle, pageUrl) ?: ""
            } catch (e: Throwable) {
                Log.e(TAG, "Native getCosmeticCss error: ${e.message}")
            }
        }

        return ""
    }
}

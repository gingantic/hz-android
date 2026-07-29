package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import android.util.Log
import com.rhnxdev.hzplayer.browser.BrowserSettings
import java.util.concurrent.atomic.AtomicLong

/**
 * High-performance thread-safe AdBlock engine strictly dependent on Brave's native adblock-rust via JNI.
 */
object AdBlockEngine {

    private const val TAG = "AdBlockEngine"

    private val nativeEnginePtr = AtomicLong(0L)

    val blockedCount = AtomicLong(0)
    @Volatile var totalRuleCount: Int = 0
        private set

    val isAvailable: Boolean
        get() = AdBlockNative.isLibraryLoaded

    val unavailableReason: String
        get() = if (!isAvailable) {
            "AdBlock is not available because the native engine (libadblock_jni.so) is missing or not supported on this device architecture."
        } else ""

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

    fun initialize(context: Context, settings: BrowserSettings) {
        reload(context, settings)
    }

    fun reload(context: Context, settings: BrowserSettings) {
        val oldPtr = nativeEnginePtr.getAndSet(0L)
        if (oldPtr != 0L && AdBlockNative.isLibraryLoaded) {
            try {
                AdBlockNative.nativeDestroyEngine(oldPtr)
            } catch (e: Throwable) {
                Log.e(TAG, "Error destroying native engine: ${e.message}")
            }
        }

        if (!isAvailable || !settings.adBlockEnabled) {
            totalRuleCount = 0
            return
        }

        val filterContents = AdBlockListManager.readActiveFilterContents(
            context = context,
            enabledListIds = settings.enabledFilterLists,
            customRules = settings.customAdBlockRules,
        )

        if (filterContents.isNotEmpty()) {
            try {
                val ptr = AdBlockNative.nativeCreateEngine(filterContents.toTypedArray())
                if (ptr != 0L) {
                    nativeEnginePtr.set(ptr)
                    totalRuleCount = filterContents.sumOf { content ->
                        content.lineSequence().count { line ->
                            line.isNotBlank() && !line.startsWith("!") && !line.startsWith("[")
                        }
                    }
                    Log.i(TAG, "Native adblock-rust engine loaded successfully ($totalRuleCount estimated rules)")
                } else {
                    Log.e(TAG, "Failed to initialize native adblock engine")
                    totalRuleCount = 0
                }
            } catch (e: Throwable) {
                Log.e(TAG, "Failed to initialize native adblock engine: ${e.message}")
                totalRuleCount = 0
            }
        } else {
            totalRuleCount = 0
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
        if (isCaptchaRequest(requestUrl)) return false

        val ptr = nativeEnginePtr.get()
        if (ptr != 0L) {
            try {
                val blocked = AdBlockNative.nativeShouldBlock(
                    enginePtr = ptr,
                    requestUrl = requestUrl,
                    pageUrl = pageUrl,
                    resourceType = resourceType
                )
                if (blocked) {
                    blockedCount.incrementAndGet()
                }
                return blocked
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

        val ptr = nativeEnginePtr.get()
        if (ptr != 0L) {
            try {
                return AdBlockNative.nativeGetCosmeticCss(ptr, pageUrl) ?: ""
            } catch (e: Throwable) {
                Log.e(TAG, "Native getCosmeticCss error: ${e.message}")
            }
        }

        return ""
    }
}

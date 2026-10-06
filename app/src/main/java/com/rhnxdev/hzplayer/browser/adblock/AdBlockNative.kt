package com.rhnxdev.hzplayer.browser.adblock

/**
 * Low-level JNI interface to Brave's adblock-rust native engine.
 *
 * Values returned by [nativeCreateEngine] are opaque handles, not pointers: the
 * native side keeps the engine registered and frees it once the last in-flight
 * request finishes after [nativeDestroyEngine].
 */
object AdBlockNative {

    @Volatile
    var isLibraryLoaded: Boolean = false
        private set

    init {
        try {
            System.loadLibrary("adblock_jni")
            isLibraryLoaded = true
        } catch (_: Throwable) {
            isLibraryLoaded = false
        }
    }

    @JvmStatic
    external fun nativeCreateEngine(rules: Array<String>): Long

    @JvmStatic
    external fun nativeShouldBlock(
        engineHandle: Long,
        requestUrl: String,
        pageUrl: String,
        resourceType: String
    ): Boolean

    @JvmStatic
    external fun nativeGetCosmeticCss(
        engineHandle: Long,
        pageUrl: String
    ): String

    @JvmStatic
    external fun nativeDestroyEngine(engineHandle: Long)
}

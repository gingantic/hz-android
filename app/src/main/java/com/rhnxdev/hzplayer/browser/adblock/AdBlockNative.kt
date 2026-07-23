package com.rhnxdev.hzplayer.browser.adblock

/**
 * Low-level JNI interface to Brave's adblock-rust native engine.
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
        enginePtr: Long,
        requestUrl: String,
        pageUrl: String,
        resourceType: String
    ): Boolean

    @JvmStatic
    external fun nativeGetCosmeticCss(
        enginePtr: Long,
        pageUrl: String
    ): String

    @JvmStatic
    external fun nativeDestroyEngine(enginePtr: Long)
}

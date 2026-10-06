package com.rhnxdev.hzplayer.browser.media

import android.content.Intent
import android.view.View
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.rhnxdev.hzplayer.WebViewHostActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Exercises the media sniffer against a real WebView hosted in a real window.
 *
 * Robolectric cannot cover this: its WebView shadows never execute JavaScript,
 * so neither the `@JavascriptInterface` token gate nor the injected sniffer
 * script can be tested off-device.
 *
 * Debug-only: the host activity lives in the debug source set (a WebView must be
 * attached to a window for `View.post` to run — see [WebViewHostActivity]).
 */
@RunWith(AndroidJUnit4::class)
class MediaSnifferBridgeInstrumentedTest {

    private lateinit var activity: WebViewHostActivity
    private lateinit var webView: WebView

    @Before
    fun launchHostActivity() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        // NEW_TASK is required when starting from the (application) target context;
        // MonitoringInstrumentation does not set it for us.
        activity = instrumentation.startActivitySync(
            Intent(instrumentation.targetContext, WebViewHostActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        ) as WebViewHostActivity
        webView = activity.webView
        runOnMain { webView.settings.javaScriptEnabled = true }
        awaitWindowAttachment()
    }

    /** `View.post` only runs once the WebView is attached — the tests rely on it. */
    private fun awaitWindowAttachment() {
        val attached = CountDownLatch(1)
        runOnMain {
            if (webView.isAttachedToWindow) {
                attached.countDown()
            } else {
                webView.addOnAttachStateChangeListener(
                    object : View.OnAttachStateChangeListener {
                        override fun onViewAttachedToWindow(v: View) = attached.countDown()
                        override fun onViewDetachedFromWindow(v: View) = Unit
                    }
                )
            }
        }
        assertTrue("WebView never attached to a window", attached.await(10, TimeUnit.SECONDS))
    }

    @After
    fun finishHostActivity() {
        if (::activity.isInitialized) {
            runOnMain { activity.finish() }
        }
    }

    @Test
    fun validTokenDeliversMediaUrl() {
        val detected = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        registerBridge(token = TOKEN, detected = detected, latch = latch)
        loadPage()

        evaluateJs("window.$INTERFACE.onMediaFound('$TOKEN', '$VIDEO_URL', 'Movie', 'video/mp4');")

        assertTrue("bridge call was not delivered", latch.await(5, TimeUnit.SECONDS))
        assertEquals(listOf(VIDEO_URL), detected)
    }

    @Test
    fun wrongTokenIsDropped() {
        val detected = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        registerBridge(token = TOKEN, detected = detected, latch = latch)
        loadPage()

        evaluateJs("window.$INTERFACE.onMediaFound('forged-token', '$VIDEO_URL', '', '');")

        assertFalse("call with a wrong token must be dropped", latch.await(2, TimeUnit.SECONDS))
        assertTrue(detected.isEmpty())
    }

    @Test
    fun injectedSnifferReportsVideoElement() {
        val detected = CopyOnWriteArrayList<String>()
        val latch = CountDownLatch(1)
        registerBridge(token = TOKEN, detected = detected, latch = latch)

        // Injects the real sniffer script (token substitution included).
        loadPage { view -> MediaSnifferBridge.injectSnifferJs(view, TOKEN) }

        // The injection is dispatched before this call, so the src setter hook is
        // installed; even if it weren't, the script's initial scan would still
        // pick the element up.
        evaluateJs(
            "var v = document.createElement('video');" +
                "v.src = '$VIDEO_URL';" +
                "document.body.appendChild(v);"
        )

        assertTrue(
            "injected sniffer did not report the video element",
            latch.await(10, TimeUnit.SECONDS)
        )
        assertEquals(VIDEO_URL, detected.first())
    }

    private fun registerBridge(
        token: String,
        detected: MutableList<String>,
        latch: CountDownLatch,
    ) {
        val bridge = MediaSnifferBridge(
            tokenValidator = { it == token },
            onMediaDetected = { url, _, _, _ ->
                detected += url
                latch.countDown()
            },
        )
        runOnMain { webView.addJavascriptInterface(bridge, MediaSnifferBridge.INTERFACE_NAME) }
    }

    private fun loadPage(onFinished: (WebView) -> Unit = {}) {
        val pageReady = CountDownLatch(1)
        runOnMain {
            webView.webViewClient = object : WebViewClient() {
                override fun onPageFinished(view: WebView, url: String) {
                    onFinished(view)
                    pageReady.countDown()
                }
            }
            webView.loadDataWithBaseURL(BASE_URL, BLANK_HTML, "text/html", "utf-8", null)
        }
        assertTrue("page never finished loading", pageReady.await(15, TimeUnit.SECONDS))
    }

    private fun evaluateJs(script: String) {
        runOnMain { webView.evaluateJavascript(script, null) }
    }

    private fun runOnMain(block: () -> Unit) {
        InstrumentationRegistry.getInstrumentation().runOnMainSync(block)
    }

    private companion object {
        const val TOKEN = "instrumented-token"
        const val INTERFACE = MediaSnifferBridge.INTERFACE_NAME
        const val VIDEO_URL = "https://cdn.example.com/movie.mp4"
        const val BASE_URL = "https://example.com/"
        const val BLANK_HTML = "<html><body></body></html>"
    }
}

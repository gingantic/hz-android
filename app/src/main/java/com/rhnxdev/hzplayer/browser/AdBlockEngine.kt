package com.rhnxdev.hzplayer.browser

import android.content.Context
import android.net.Uri
import android.webkit.WebResourceResponse
import android.webkit.WebView
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.ByteArrayInputStream
import java.io.File
import java.io.InputStream
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Advanced uBlock Origin engine.
 * Loads and parses official uBlock Origin uAssets filters (filters.txt, privacy.txt, badware.txt, unbreak.txt).
 * Handles host matching, allowlists/unbreak rules, tracker blocking, and cosmetic element hiding.
 */
object AdBlockEngine {

    val totalBlockedCount = AtomicLong(0)
    val totalRulesLoaded = AtomicLong(0)

    val UBLOCK_UASSETS_URLS = listOf(
        "https://ublockorigin.github.io/uAssets/filters/filters.txt",
        "https://ublockorigin.github.io/uAssets/filters/privacy.txt",
        "https://ublockorigin.github.io/uAssets/filters/badware.txt",
        "https://ublockorigin.github.io/uAssets/filters/unbreak.txt"
    )

    private val isInitializing = AtomicBoolean(false)
    private val isLoaded = AtomicBoolean(false)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val adDomains = ConcurrentHashMap.newKeySet<String>()
    private val trackerDomains = ConcurrentHashMap.newKeySet<String>()
    private val allowDomains = ConcurrentHashMap.newKeySet<String>()
    private val adPathPatterns = ConcurrentHashMap.newKeySet<String>()
    private val cosmeticSelectors = ConcurrentHashMap.newKeySet<String>()

    private val DUMMY_RESPONSE by lazy {
        WebResourceResponse("text/plain", "UTF-8", ByteArrayInputStream(ByteArray(0)))
    }

    private const val ASSET_FILE = "adblock_hosts.txt"
    private const val LOCAL_CACHE_FILE = "ublock_uassets_cached.txt"

    @Volatile
    private var cachedCosmeticJs: String = ""

    /**
     * Initialize engine asynchronously with uBlock filter rules from assets or cache.
     */
    fun init(context: Context) {
        if (isLoaded.get() || !isInitializing.compareAndSet(false, true)) return

        scope.launch {
            try {
                val appContext = context.applicationContext
                val cacheFile = File(appContext.filesDir, LOCAL_CACHE_FILE)
                val inputStream: InputStream = if (cacheFile.exists() && cacheFile.length() > 0) {
                    cacheFile.inputStream()
                } else {
                    appContext.assets.open(ASSET_FILE)
                }

                loadRulesFromStream(inputStream)
                isLoaded.set(true)
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                isInitializing.set(false)
            }
        }
    }

    /**
     * Parse uBlock Origin filter rules stream.
     */
    suspend fun loadRulesFromStream(inputStream: InputStream) = withContext(Dispatchers.IO) {
        val reader = BufferedReader(InputStreamReader(inputStream))

        reader.useLines { lines ->
            lines.forEach { rawLine ->
                val line = rawLine.trim()
                if (line.isEmpty() || line.startsWith("!") || line.startsWith("#")) {
                    return@forEach
                }

                // 1. uBlock Allow / Unbreak Rule (e.g. @@||example.com^ or @@||google.com^$image)
                if (line.startsWith("@@||")) {
                    var domain = line.substring(4)
                    val endIdx = domain.indexOfAny(charArrayOf('^', '$', '/'))
                    if (endIdx != -1) {
                        domain = domain.substring(0, endIdx)
                    }
                    domain = domain.lowercase().trim()
                    if (domain.isNotBlank()) {
                        allowDomains.add(domain)
                    }
                    return@forEach
                }

                // 2. uBlock / EasyList Cosmetic Filtering Rule (e.g. ##.ad-banner or ###ad_slot)
                if (line.contains("##")) {
                    val selector = line.substringAfter("##").trim()
                    if (selector.isNotBlank() && !selector.startsWith("#@#")) {
                        cosmeticSelectors.add(selector)
                    }
                    return@forEach
                }

                // 3. uBlock / EasyList Domain Blocking Rule (e.g. ||doubleclick.net^)
                if (line.startsWith("||")) {
                    var domain = line.substring(2)
                    val endIdx = domain.indexOfAny(charArrayOf('^', '$', '/'))
                    if (endIdx != -1) {
                        domain = domain.substring(0, endIdx)
                    }
                    domain = domain.lowercase().trim()
                    if (domain.isNotBlank()) {
                        if (domain.contains("analytics") || domain.contains("tracker") || domain.contains("telemetry")) {
                            trackerDomains.add(domain)
                        } else {
                            adDomains.add(domain)
                        }
                    }
                    return@forEach
                }

                // 4. Hosts Format Rule (e.g. 0.0.0.0 ad.doubleclick.net or 127.0.0.1 pagead2.googlesyndication.com)
                if (line.startsWith("0.0.0.0 ") || line.startsWith("127.0.0.1 ")) {
                    val tokens = line.split("\\s+".toRegex())
                    if (tokens.size >= 2) {
                        val host = tokens[1].lowercase().trim()
                        if (host.isNotBlank() && host != "localhost") {
                            adDomains.add(host)
                        }
                    }
                    return@forEach
                }

                // 5. Path Pattern or Plain Host
                if (line.startsWith("/")) {
                    adPathPatterns.add(line.lowercase())
                } else if (!line.contains(" ")) {
                    val cleanHost = line.lowercase().trim()
                    if (cleanHost.contains(".")) {
                        adDomains.add(cleanHost)
                    }
                }
            }
        }

        totalRulesLoaded.set(
            adDomains.size.toLong() +
            trackerDomains.size.toLong() +
            allowDomains.size.toLong() +
            cosmeticSelectors.size.toLong()
        )
        buildCosmeticJs()
    }

    /**
     * Check if a request URL matches uBlock filter rules.
     */
    fun shouldBlockUrl(urlStr: String, blockTrackers: Boolean): Boolean {
        if (urlStr.isBlank()) return false
        val uri = try {
            Uri.parse(urlStr)
        } catch (e: Exception) {
            return false
        }

        val host = uri.host?.lowercase() ?: return false
        val scheme = uri.scheme?.lowercase() ?: ""
        if (scheme != "http" && scheme != "https") return false

        // First check allowlist / unbreak rules (@@||domain^)
        if (isHostInSet(host, allowDomains)) {
            return false
        }

        // Check host match against ad/tracker domain sets
        if (isHostInSet(host, adDomains)) {
            totalBlockedCount.incrementAndGet()
            return true
        }

        if (blockTrackers && isHostInSet(host, trackerDomains)) {
            totalBlockedCount.incrementAndGet()
            return true
        }

        // Check URL path patterns
        val path = uri.path?.lowercase() ?: ""
        val fullUrl = urlStr.lowercase()
        for (pattern in adPathPatterns) {
            if (path.contains(pattern) || fullUrl.contains(pattern)) {
                totalBlockedCount.incrementAndGet()
                return true
            }
        }

        return false
    }

    /**
     * Returns an empty response to block the intercepted resource.
     */
    fun createDummyResponse(): WebResourceResponse = DUMMY_RESPONSE

    /**
     * Inject cosmetic CSS snippet into WebView to hide empty ad container boxes.
     */
    fun injectCosmeticFilter(view: WebView) {
        if (cachedCosmeticJs.isBlank()) return
        try {
            view.evaluateJavascript(cachedCosmeticJs, null)
        } catch (_: Exception) {
        }
    }

    /**
     * Download latest filter lists from official uBlock Origin uAssets repository.
     */
    fun updateRulesOnline(context: Context, customUrls: List<String>? = null, onComplete: ((Boolean) -> Unit)? = null) {
        scope.launch {
            var success = false
            try {
                val urls = customUrls ?: UBLOCK_UASSETS_URLS
                val cacheFile = File(context.applicationContext.filesDir, LOCAL_CACHE_FILE)
                val tempFile = File(context.applicationContext.filesDir, "ublock_temp.txt")
                
                tempFile.outputStream().buffered().use { out ->
                    for (targetUrl in urls) {
                        try {
                            val conn = URL(targetUrl).openConnection() as HttpURLConnection
                            conn.connectTimeout = 8000
                            conn.readTimeout = 12000
                            conn.requestMethod = "GET"

                            if (conn.responseCode == HttpURLConnection.HTTP_OK) {
                                conn.inputStream.use { input ->
                                    input.copyTo(out)
                                }
                                out.write("\n".toByteArray())
                                success = true
                            }
                        } catch (e: Exception) {
                            e.printStackTrace()
                        }
                    }
                }

                if (success && tempFile.length() > 0) {
                    tempFile.copyTo(cacheFile, overwrite = true)
                    tempFile.delete()
                    adDomains.clear()
                    trackerDomains.clear()
                    allowDomains.clear()
                    cosmeticSelectors.clear()
                    loadRulesFromStream(cacheFile.inputStream())
                }
            } catch (e: Exception) {
                e.printStackTrace()
            } finally {
                withContext(Dispatchers.Main) {
                    onComplete?.invoke(success)
                }
            }
        }
    }

    /**
     * Reset blocked request counter.
     */
    fun resetStats() {
        totalBlockedCount.set(0)
    }

    private fun buildCosmeticJs() {
        if (cosmeticSelectors.isEmpty()) return
        val joined = cosmeticSelectors.joinToString(", ")
        cachedCosmeticJs = """
            (function() {
                var css = "$joined { display: none !important; visibility: hidden !important; height: 0 !important; opacity: 0 !important; pointer-events: none !important; }";
                var head = document.head || document.getElementsByTagName('head')[0];
                if (head) {
                    var style = document.createElement('style');
                    style.type = 'text/css';
                    style.appendChild(document.createTextNode(css));
                    head.appendChild(style);
                }
            })();
        """.trimIndent().replace("\n", " ")
    }

    private fun isHostInSet(host: String, set: Set<String>): Boolean {
        if (set.contains(host)) return true
        var domain = host
        while (domain.contains(".")) {
            val nextDot = domain.indexOf('.')
            if (nextDot == -1 || nextDot == domain.length - 1) break
            domain = domain.substring(nextDot + 1)
            if (set.contains(domain)) return true
        }
        return false
    }
}

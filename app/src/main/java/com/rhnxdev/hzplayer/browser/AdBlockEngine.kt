package com.rhnxdev.hzplayer.browser.adblock

import android.content.Context
import android.net.Uri
import com.rhnxdev.hzplayer.browser.BrowserSettings
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * High-performance thread-safe AdBlock engine mirroring uBlock Origin concepts.
 */
object AdBlockEngine {

    private val ruleSetRef = AtomicReference(CompiledRuleSet())
    val blockedCount = AtomicLong(0)
    @Volatile var totalRuleCount: Int = 0
        private set

    @Volatile private var isInitialized = false

    fun initialize(context: Context, settings: BrowserSettings) {
        reload(context, settings)
        isInitialized = true
    }

    fun reload(context: Context, settings: BrowserSettings) {
        if (!settings.adBlockEnabled) {
            ruleSetRef.set(CompiledRuleSet())
            totalRuleCount = 0
            return
        }

        val compiled = AdBlockListManager.compileActiveRules(
            context = context,
            enabledListIds = settings.enabledFilterLists,
            customRules = settings.customAdBlockRules,
        )

        ruleSetRef.set(compiled)

        totalRuleCount = compiled.exactBlockedDomains.size +
                compiled.networkBlockRules.size +
                compiled.globalCosmeticSelectors.size +
                compiled.domainCosmeticRules.values.sumOf { it.size }
    }

    /**
     * Checks if a network request URL should be blocked.
     */
    fun shouldBlockRequest(
        requestUrl: String,
        pageUrl: String = "",
        settings: BrowserSettings,
    ): Boolean {
        if (!settings.adBlockEnabled || requestUrl.isBlank()) return false
        if (requestUrl.startsWith("data:") || requestUrl.startsWith("blob:") || requestUrl.startsWith("file:")) return false

        val ruleSet = ruleSetRef.get()
        val requestHost = extractHost(requestUrl)

        if (requestHost.isNotEmpty()) {
            // 1. Whitelist / Exception Host check
            if (isDomainMatch(requestHost, ruleSet.exactExceptionDomains)) {
                return false
            }

            // 2. Exact Blocked Host check
            if (isDomainMatch(requestHost, ruleSet.exactBlockedDomains)) {
                blockedCount.incrementAndGet()
                return true
            }
        }

        val pageHost = extractHost(pageUrl)
        val isThirdParty = requestHost.isNotEmpty() && pageHost.isNotEmpty() && !isSameRootDomain(requestHost, pageHost)

        // 3. Exception Network Rules check
        for (rule in ruleSet.networkExceptionRules) {
            if (matchesNetworkRule(rule, requestUrl, requestHost, pageHost, isThirdParty)) {
                return false
            }
        }

        // 4. Block Network Rules check
        for (rule in ruleSet.networkBlockRules) {
            if (matchesNetworkRule(rule, requestUrl, requestHost, pageHost, isThirdParty)) {
                blockedCount.incrementAndGet()
                return true
            }
        }

        return false
    }

    /**
     * Generates cosmetic element hiding CSS rules for a given web page URL.
     */
    fun getCosmeticCss(pageUrl: String, settings: BrowserSettings): String {
        if (!settings.adBlockEnabled || !settings.cosmeticFilteringEnabled || pageUrl.isBlank()) {
            return ""
        }

        val ruleSet = ruleSetRef.get()
        val pageHost = extractHost(pageUrl)
        val rootDomain = getRootDomain(pageHost)

        val selectors = mutableSetOf<String>()

        // 1. Global cosmetic selectors
        selectors.addAll(ruleSet.globalCosmeticSelectors)

        // 2. Domain specific cosmetic rules
        if (pageHost.isNotEmpty()) {
            val domainRules = (ruleSet.domainCosmeticRules[pageHost] ?: emptyList()) +
                    (ruleSet.domainCosmeticRules[rootDomain] ?: emptyList())

            for (rule in domainRules) {
                if (rule.excludedDomains.contains(pageHost) || rule.excludedDomains.contains(rootDomain)) {
                    continue
                }
                if (rule.isException) {
                    selectors.remove(rule.selector)
                } else {
                    selectors.add(rule.selector)
                }
            }
        }

        if (selectors.isEmpty()) return ""

        // Chunk into dynamic CSS rules (limit max length to prevent giant JS injection)
        val cssJoined = selectors.take(500).joinToString(", ")
        return "$cssJoined { display: none !important; visibility: hidden !important; height: 0 !important; max-height: 0 !important; opacity: 0 !important; pointer-events: none !important; }"
    }

    private fun matchesNetworkRule(
        rule: NetworkRule,
        url: String,
        requestHost: String,
        pageHost: String,
        isThirdParty: Boolean
    ): Boolean {
        if (rule.isThirdPartyOnly && !isThirdParty) return false

        if (rule.targetDomains.isNotEmpty()) {
            val matchedTarget = rule.targetDomains.any { isDomainMatch(pageHost, setOf(it)) }
            if (!matchedTarget) return false
        }

        if (rule.excludedDomains.isNotEmpty()) {
            val matchedExcluded = rule.excludedDomains.any { isDomainMatch(pageHost, setOf(it)) }
            if (matchedExcluded) return false
        }

        if (rule.isDomainAnchor) {
            if (!isDomainMatch(requestHost, setOf(rule.pattern))) return false
        }

        val regex = rule.regexPattern
        if (regex != null) {
            return regex.containsMatchIn(url)
        }

        return url.lowercase(Locale.ROOT).contains(rule.pattern.lowercase(Locale.ROOT))
    }

    private fun extractHost(urlStr: String): String {
        return try {
            val uri = Uri.parse(urlStr)
            uri.host?.lowercase(Locale.ROOT) ?: ""
        } catch (_: Exception) {
            ""
        }
    }

    private fun isDomainMatch(host: String, domainSet: Set<String>): Boolean {
        if (host.isEmpty() || domainSet.isEmpty()) return false
        if (domainSet.contains(host)) return true

        var current = host
        while (current.contains(".")) {
            current = current.substringAfter(".")
            if (domainSet.contains(current)) return true
        }
        return false
    }

    private fun getRootDomain(host: String): String {
        if (host.isEmpty()) return ""
        val parts = host.split(".")
        return if (parts.size >= 2) {
            parts.takeLast(2).joinToString(".")
        } else host
    }

    private fun isSameRootDomain(host1: String, host2: String): Boolean {
        return getRootDomain(host1) == getRootDomain(host2)
    }
}

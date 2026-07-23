package com.rhnxdev.hzplayer.browser.adblock

import java.util.Locale

/**
 * Parsed Network Filter Rule.
 */
data class NetworkRule(
    val pattern: String,
    val isException: Boolean = false,
    val isDomainAnchor: Boolean = false, // Starts with ||
    val isExactMatch: Boolean = false,
    val isThirdPartyOnly: Boolean = false,
    val targetDomains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
    val regexPattern: Regex? = null,
)

/**
 * Parsed Cosmetic / Element Hiding Rule.
 */
data class CosmeticRule(
    val selector: String,
    val isException: Boolean = false,
    val targetDomains: Set<String> = emptySet(),
    val excludedDomains: Set<String> = emptySet(),
)

/**
 * Compiled Rule Set containing categorized rules for fast lookup.
 */
data class CompiledRuleSet(
    val exactBlockedDomains: Set<String> = emptySet(),
    val exactExceptionDomains: Set<String> = emptySet(),
    val networkBlockRules: List<NetworkRule> = emptyList(),
    val networkExceptionRules: List<NetworkRule> = emptyList(),
    val globalCosmeticSelectors: Set<String> = emptySet(),
    val domainCosmeticRules: Map<String, List<CosmeticRule>> = emptyMap(),
)

object AdBlockRuleParser {

    /**
     * Parses raw filter list content (EasyList / uBlock / Hosts format) into a [CompiledRuleSet].
     */
    fun parseRules(content: String): CompiledRuleSet {
        val exactBlockedDomains = mutableSetOf<String>()
        val exactExceptionDomains = mutableSetOf<String>()
        val networkBlockRules = mutableListOf<NetworkRule>()
        val networkExceptionRules = mutableListOf<NetworkRule>()
        val globalCosmeticSelectors = mutableSetOf<String>()
        val domainCosmeticRulesMap = mutableMapOf<String, MutableList<CosmeticRule>>()

        for (rawLine in content.lineSequence()) {
            val line = rawLine.trim()
            if (line.isEmpty() || line.startsWith("!") || line.startsWith("[")) continue

            // 1. Check Hosts format (e.g. 127.0.0.1 adserver.com or 0.0.0.0 adserver.com)
            if (line.startsWith("127.0.0.1") || line.startsWith("0.0.0.0")) {
                val parts = line.split(Regex("\\s+"))
                if (parts.size >= 2) {
                    val domain = parts[1].lowercase(Locale.ROOT)
                    if (domain != "localhost" && domain != "broadcasthost") {
                        exactBlockedDomains.add(domain)
                    }
                }
                continue
            }

            // 2. Check Cosmetic / Element Hiding rules (contains ##, #@#)
            if (line.contains("##") || line.contains("#@#")) {
                val isException = line.contains("#@#")
                val separator = if (isException) "#@#" else "##"
                val split = line.split(separator, limit = 2)
                if (split.size == 2) {
                    val domainPart = split[0].trim()
                    val selectorPart = split[1].trim()

                    if (selectorPart.isNotEmpty() && !selectorPart.startsWith("+js")) {
                        if (domainPart.isEmpty()) {
                            // Global cosmetic rule
                            if (!isException) {
                                globalCosmeticSelectors.add(selectorPart)
                            }
                        } else {
                            // Domain-specific cosmetic rule
                            val (targetDomains, excludedDomains) = parseDomainOptions(domainPart)
                            val rule = CosmeticRule(
                                selector = selectorPart,
                                isException = isException,
                                targetDomains = targetDomains,
                                excludedDomains = excludedDomains,
                            )
                            targetDomains.forEach { domain ->
                                domainCosmeticRulesMap.getOrPut(domain) { mutableListOf() }.add(rule)
                            }
                        }
                    }
                }
                continue
            }

            // 3. Network Filter Rules
            var isException = false
            var ruleText = line

            if (ruleText.startsWith("@@")) {
                isException = true
                ruleText = ruleText.substring(2)
            }

            // Parse options suffix ($script,image,domain=...)
            var optionsPart = ""
            if (ruleText.contains("$")) {
                val dollarsIndex = ruleText.lastIndexOf('$')
                optionsPart = ruleText.substring(dollarsIndex + 1)
                ruleText = ruleText.substring(0, dollarsIndex)
            }

            if (ruleText.isEmpty()) continue

            var isThirdPartyOnly = false
            var targetDomains = emptySet<String>()
            var excludedDomains = emptySet<String>()

            if (optionsPart.isNotEmpty()) {
                val options = optionsPart.split(',')
                for (opt in options) {
                    val trimmedOpt = opt.trim()
                    if (trimmedOpt.equals("third-party", ignoreCase = true) || trimmedOpt.equals("3p", ignoreCase = true)) {
                        isThirdPartyOnly = true
                    } else if (trimmedOpt.startsWith("domain=", ignoreCase = true)) {
                        val domainsStr = trimmedOpt.substring(7)
                        val parsed = parseDomainOptions(domainsStr)
                        targetDomains = parsed.first
                        excludedDomains = parsed.second
                    }
                }
            }

            // Fast path for domain anchor ||domain.com^
            if (ruleText.startsWith("||") && ruleText.endsWith("^") && !ruleText.contains("/") && !ruleText.contains("*")) {
                val domain = ruleText.substring(2, ruleText.length - 1).lowercase(Locale.ROOT)
                if (domain.isNotEmpty()) {
                    if (isException) {
                        exactExceptionDomains.add(domain)
                    } else {
                        exactBlockedDomains.add(domain)
                    }
                    continue
                }
            }

            // Standard network rule with wildcards / patterns
            val isDomainAnchor = ruleText.startsWith("||")
            val cleanPattern = if (isDomainAnchor) ruleText.substring(2) else ruleText

            val regex = try {
                val convertedPattern = cleanPattern
                    .replace("^", "(?:[^a-zA-Z0-9_\\.%-]|\\$)")
                    .replace("*", ".*")
                Regex(convertedPattern, RegexOption.IGNORE_CASE)
            } catch (_: Exception) {
                null
            }

            val netRule = NetworkRule(
                pattern = cleanPattern,
                isException = isException,
                isDomainAnchor = isDomainAnchor,
                isThirdPartyOnly = isThirdPartyOnly,
                targetDomains = targetDomains,
                excludedDomains = excludedDomains,
                regexPattern = regex,
            )

            if (isException) {
                networkExceptionRules.add(netRule)
            } else {
                networkBlockRules.add(netRule)
            }
        }

        return CompiledRuleSet(
            exactBlockedDomains = exactBlockedDomains,
            exactExceptionDomains = exactExceptionDomains,
            networkBlockRules = networkBlockRules,
            networkExceptionRules = networkExceptionRules,
            globalCosmeticSelectors = globalCosmeticSelectors,
            domainCosmeticRules = domainCosmeticRulesMap,
        )
    }

    private fun parseDomainOptions(domainStr: String): Pair<Set<String>, Set<String>> {
        val targets = mutableSetOf<String>()
        val excluded = mutableSetOf<String>()

        domainStr.split('|', ',').forEach { item ->
            val trimmed = item.trim().lowercase(Locale.ROOT)
            if (trimmed.startsWith("~")) {
                val domain = trimmed.substring(1)
                if (domain.isNotEmpty()) excluded.add(domain)
            } else if (trimmed.isNotEmpty()) {
                targets.add(trimmed)
            }
        }
        return Pair(targets, excluded)
    }
}

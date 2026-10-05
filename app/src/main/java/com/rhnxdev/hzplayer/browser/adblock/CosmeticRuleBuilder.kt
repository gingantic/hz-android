package com.rhnxdev.hzplayer.browser.adblock

/**
 * Builds cosmetic filter rules (`domain##selector`) for elements the user
 * blocked with the element picker.
 *
 * Selectors are always tag-first: a bare `##.class` / `##id` rule is stored in
 * engine buckets this app never queries, so it would silently never match.
 */
object CosmeticRuleBuilder {

    /** Guards the free-text custom-rules blob against a pathological selector. */
    private const val MAX_SELECTOR_LENGTH = 512

    /**
     * Cosmetic rule hiding [selector] on [host] (or on every site when
     * [applyToAllSites]). Returns null when the selector can't form a valid rule.
     */
    fun build(host: String?, selector: String, applyToAllSites: Boolean): String? {
        val s = selector.trim()
        if (s.isBlank() || s.length > MAX_SELECTOR_LENGTH) return null
        // `##` starts the selector half of a rule, so a second one would split the
        // line and make the domain half swallow the real selector.
        if (s.contains("##") || s.contains('\n') || s.contains('\r')) return null

        val domain = if (applyToAllSites) {
            null
        } else {
            host?.trim()?.lowercase()?.takeIf { it.isNotBlank() }
        }
        return if (domain == null) "##$s" else "$domain##$s"
    }

    /** True when [rule] is already one of the lines in [rules]. */
    fun contains(rules: String, rule: String): Boolean =
        rules.lineSequence().any { it.trim() == rule }

    /** Add [rule] as a new line at the end of [rules]. */
    fun append(rules: String, rule: String): String =
        if (rules.isBlank()) rule else rules.trimEnd() + "\n" + rule
}

package com.rhnxdev.hzplayer.browser.adblock

/**
 * Randomness heuristic for the element-picker CSS-selector generator: decides
 * whether an id/class token is a per-page-load random string (so a rule built
 * on it would never match again) and extracts the stable stem of a random tail.
 *
 * Mirror of `looksRandom`/`stemOf` in `ElementPickerBridge.PICKER_JS` — the JS
 * runs in the WebView and this Kotlin copy is the unit-tested source of truth.
 * Any change here MUST be mirrored there (and vice-versa).
 */
object SelectorHeuristics {

    // Words that are stable by convention regardless of shape — never random.
    private val STABLE_ALLOWLIST: Set<String> = setOf(
        "ad", "ads", "advert", "advertisement", "banner", "promo", "promoted",
        "sponsor", "sponsored", "sidebar", "widget", "slot", "container",
        "wrapper", "overlay", "modal", "popup", "interstitial",
    )

    // pure hex blob (a1b2c3) or long base36 run (x7f9k2qp)
    private val HEX_BLOB = Regex("^[0-9a-f]{6,}$", RegexOption.IGNORE_CASE)
    private val BASE36_BLOB = Regex("^[a-z0-9]{8,}$", RegexOption.IGNORE_CASE)
    // pure numeric id (6+ digits)
    private val NUMERIC = Regex("^[0-9]{6,}$")
    // UUID-like: hex + dashes, long
    private val UUID_LIKE = Regex("^[0-9a-f-]{20,}$", RegexOption.IGNORE_CASE)
    // stem + random alnum tail (adbox_7fa21, wrapper-9ks2x, ad-slot__container_x7f9k2):
    // group 1 is the stable stem, group 2 the tail. The digit check keeps a plain
    // word tail like `main-content` from matching. Non-greedy stem allows -/_ inside.
    private val STEM = Regex("^([A-Za-z][A-Za-z0-9_-]*?)[-_]?([0-9a-z]{5,})$", RegexOption.IGNORE_CASE)

    /**
     * True when [token] looks like a per-load random string. Conservative: a
     * ≤3-char token and any allowlisted word are never random.
     *
     * Density check for a base36 blob: too few vowels (≤1) or a high digit ratio
     * (≥0.3) marks it random — real words carry vowels and few digits.
     */
    fun looksRandom(token: String): Boolean {
        val t = token.trim()
        if (t.length <= 3) return false
        if (t.lowercase() in STABLE_ALLOWLIST) return false

        if (HEX_BLOB.matches(t)) return true
        if (NUMERIC.matches(t)) return true
        if (UUID_LIKE.matches(t) && t.any { it.isDigit() }) return true
        // stem + alnum tail: random only when the tail actually carries a digit
        STEM.matchEntire(t)?.let { if (it.groupValues[2].any { c -> c.isDigit() }) return true }
        if (BASE36_BLOB.matches(t)) {
            val vowels = t.count { it.lowercaseChar() in "aeiou" }
            val digits = t.count { it.isDigit() }
            if (vowels <= 1 || digits.toDouble() / t.length >= 0.3) return true
        }
        return false
    }

    /**
     * Stable prefix of a `stem + random tail` token (adbox_7fa21 -> adbox), for a
     * `[class*="stem"]` / `[id^="stem"]` rule. Returns null unless the stem is
     * ≥4 chars and not itself random.
     */
    fun stemOf(token: String): String? {
        val m = STEM.matchEntire(token.trim()) ?: return null
        val stem = m.groupValues[1]
        val tail = m.groupValues[2]
        if (!tail.any { it.isDigit() }) return null
        return if (stem.length >= 4 && !looksRandom(stem)) stem else null
    }
}

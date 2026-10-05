package com.rhnxdev.hzplayer.browser.adblock

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CosmeticRuleBuilderTest {

    @Test
    fun testScopedRuleUsesLowercaseHost() {
        val rule = CosmeticRuleBuilder.build(
            host = "Example.COM",
            selector = "div.ad-slot",
            applyToAllSites = false,
        )
        assertEquals("example.com##div.ad-slot", rule)
    }

    @Test
    fun testAllSitesRuleHasEmptyDomain() {
        val rule = CosmeticRuleBuilder.build(
            host = "example.com",
            selector = "div.ad-slot",
            applyToAllSites = true,
        )
        assertEquals("##div.ad-slot", rule)
    }

    @Test
    fun testAllSitesFallsBackWhenHostMissing() {
        val rule = CosmeticRuleBuilder.build(
            host = null,
            selector = "div.ad-slot",
            applyToAllSites = false,
        )
        assertEquals("##div.ad-slot", rule)
    }

    @Test
    fun testBlankSelectorIsRejected() {
        assertNull(CosmeticRuleBuilder.build("example.com", "   ", false))
    }

    @Test
    fun testSelectorContainingRuleSeparatorIsRejected() {
        assertNull(CosmeticRuleBuilder.build("example.com", "div##span", false))
        assertNull(CosmeticRuleBuilder.build("example.com", "div\nspan", false))
    }

    @Test
    fun testOverlongSelectorIsRejected() {
        val long = "div" + ":nth-child(1)".repeat(60)
        assertNull(CosmeticRuleBuilder.build("example.com", long, false))
    }

    @Test
    fun testAttributeSelectorIsAccepted() {
        val rule = CosmeticRuleBuilder.build(
            host = "example.com",
            selector = "div[data-ad=\"top\"]",
            applyToAllSites = false,
        )
        assertEquals("example.com##div[data-ad=\"top\"]", rule)
    }

    @Test
    fun testClassPrefixWithStructuralPartIsAccepted() {
        val selector = "section[class*=\"ad-slot__container\"] > div:nth-of-type(2)"
        val rule = CosmeticRuleBuilder.build(
            host = "example.com",
            selector = selector,
            applyToAllSites = false,
        )
        assertEquals("example.com##$selector", rule)
    }

    @Test
    fun testContainsMatchesTrimmedLineOnly() {
        val rules = "example.com##div.a\n##div.b"
        assertTrue(CosmeticRuleBuilder.contains(rules, "##div.b"))
        assertFalse(CosmeticRuleBuilder.contains(rules, "div.b"))
        assertFalse(CosmeticRuleBuilder.contains(rules, "##div.c"))
    }

    @Test
    fun testAppendAddsRuleOnItsOwnLine() {
        assertEquals(
            "example.com##div.a\n##div.b",
            CosmeticRuleBuilder.append("example.com##div.a", "##div.b"),
        )
        assertEquals("##div.b", CosmeticRuleBuilder.append("", "##div.b"))
        assertEquals("##div.b", CosmeticRuleBuilder.append("   \n", "##div.b"))
    }
}

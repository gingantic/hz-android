package com.rhnxdev.hzplayer.data.datasource.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** Guards the per-certificate filter that keeps mbedTLS from rejecting the whole CA file. */
class NativeTlsCaBundleTest {

    private fun friendly(
        isCa: Boolean = true,
        key: String = "RSA",
        bits: Int = 0,
        sig: String = "SHA256withRSA",
        critical: Set<String>? = setOf("2.5.29.19", "2.5.29.15"),
    ) = NativeTlsCaBundle.isMbedtlsFriendly(isCa, key, bits, sig, critical)

    @Test
    fun rsaSha256Root_isKept() = assertTrue(friendly())

    @Test
    fun ecP384Root_isKept() = assertTrue(friendly(key = "EC", bits = 384, sig = "SHA384withECDSA"))

    @Test
    fun nonCa_isSkipped() = assertFalse(friendly(isCa = false))

    @Test
    fun dsaKey_isSkipped() = assertFalse(friendly(key = "DSA"))

    @Test
    fun ec192_isSkipped() = assertFalse(friendly(key = "EC", bits = 192, sig = "SHA256withECDSA"))

    @Test
    fun md5Signature_isSkipped() = assertFalse(friendly(sig = "MD5withRSA"))

    @Test
    fun unknownCriticalExtension_isSkipped() =
        assertFalse(friendly(critical = setOf("2.5.29.19", "2.5.29.30")))

    @Test
    fun noCriticalExtensions_isKept() = assertTrue(friendly(critical = null))

    @Test
    fun encodePem_hasMarkersAndShortLines() {
        val pem = NativeTlsCaBundle.encodePem(listOf(ByteArray(200) { it.toByte() }))
        assertTrue(pem.startsWith("-----BEGIN CERTIFICATE-----\n"))
        assertTrue(pem.endsWith("-----END CERTIFICATE-----\n"))
        assertTrue(pem.lines().all { it.length <= 64 || it.startsWith("-----") })
    }

    /** The writer and the cache validator must agree, or every open rebuilds the bundle. */
    @Test
    fun encodePem_outputPassesTheCacheValidator() {
        val file = tempBundle(NativeTlsCaBundle.encodePem(listOf(ByteArray(200) { it.toByte() })))
        try {
            assertTrue(NativeTlsCaBundle.isWellFormedBundle(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun truncatedBundle_isRejected() {
        val pem = NativeTlsCaBundle.encodePem(listOf(ByteArray(200) { it.toByte() }))
        val file = tempBundle(pem.dropLast(20))
        try {
            assertFalse(NativeTlsCaBundle.isWellFormedBundle(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun trailingJunkAfterTheTail_isRejected() {
        val pem = NativeTlsCaBundle.encodePem(listOf(ByteArray(200) { it.toByte() }))
        val file = tempBundle(pem + "garbage")
        try {
            assertFalse(NativeTlsCaBundle.isWellFormedBundle(file))
        } finally {
            file.delete()
        }
    }

    @Test
    fun emptyBundle_isRejected() {
        val file = tempBundle("")
        try {
            assertFalse(NativeTlsCaBundle.isWellFormedBundle(file))
        } finally {
            file.delete()
        }
    }

    private fun tempBundle(contents: String): File =
        File.createTempFile("cacert", ".pem").apply { writeText(contents, Charsets.US_ASCII) }
}

package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.UnknownHostException

class NetworkDomainUtilsTest {

    @Test
    fun isNumericIp_identifiesIPv4AndIPv6() {
        assertTrue(NetworkDomainUtils.isNumericIp("192.168.1.1"))
        assertTrue(NetworkDomainUtils.isNumericIp("10.0.0.254"))
        assertTrue(NetworkDomainUtils.isNumericIp("127.0.0.1"))
        assertTrue(NetworkDomainUtils.isNumericIp("fe80::1"))
        assertTrue(NetworkDomainUtils.isNumericIp("[::1]"))

        assertFalse(NetworkDomainUtils.isNumericIp("my-server.local"))
        assertFalse(NetworkDomainUtils.isNumericIp("nas.home.arpa"))
        assertFalse(NetworkDomainUtils.isNumericIp("example.com"))
        assertFalse(NetworkDomainUtils.isNumericIp("localhost"))
    }

    @Test
    fun resolveDomain_returnsOriginalDomainIfAlreadyDomain() {
        val domain = "my-nas.local"
        val resolved = NetworkDomainUtils.resolveDomain(null, domain)
        assertEquals("my-nas.local", resolved)
    }

    @Test
    fun resolveDomain_returnsIpIfResolutionFailsOrSame() {
        // TEST-NET-3 (RFC 5737) is never assigned to a host, so its reverse
        // lookup always fails. A private IP would resolve on CI runners whose
        // VNet actually owns that address, making this test environment-bound.
        val ip = "203.0.113.254"
        val resolved = NetworkDomainUtils.resolveDomain(null, ip)
        assertEquals("203.0.113.254", resolved)
    }

    @Test
    fun verifiedHostName_acceptsAnyAddressOfADualStackName() {
        val ipv6 = InetAddress.getByName("fe80::1")
        val ipv4 = InetAddress.getByName("192.168.1.10")
        val verified = NetworkDomainUtils.verifiedHostName("nas.lan", "192.168.1.10") {
            arrayOf(ipv6, ipv4)
        }
        assertEquals("nas.lan", verified)
    }

    @Test
    fun verifiedHostName_rejectsNameResolvingToAnotherHost() {
        val other = InetAddress.getByName("192.168.1.99")
        val verified = NetworkDomainUtils.verifiedHostName("nas.lan", "192.168.1.10") { arrayOf(other) }
        assertNull(verified)
    }

    @Test
    fun verifiedHostName_triesMdnsSuffixForBareName() {
        val ip = InetAddress.getByName("192.168.1.10")
        val verified = NetworkDomainUtils.verifiedHostName("nas", "192.168.1.10") { form ->
            if (form == "nas.local") arrayOf(ip) else throw UnknownHostException(form)
        }
        assertEquals("nas.local", verified)
    }

    @Test
    fun verifiedHostName_ignoresNumericAndBlankCandidates() {
        assertNull(NetworkDomainUtils.verifiedHostName("192.168.1.10", "192.168.1.10") { arrayOf() })
        assertNull(NetworkDomainUtils.verifiedHostName("   ", "192.168.1.10") { arrayOf() })
    }

    @Test
    fun resolveDomain_usesInetAddressHostNameWhenDifferent() {
        // Create an InetAddress mock or loopback check
        val loopback = InetAddress.getByName("127.0.0.1")
        val resolved = NetworkDomainUtils.resolveDomain(loopback, "127.0.0.1")
        // If 127.0.0.1 resolves to localhost, resolved should be localhost
        if (loopback.hostName != "127.0.0.1") {
            assertEquals(loopback.hostName, resolved)
        } else {
            assertEquals("127.0.0.1", resolved)
        }
    }
}

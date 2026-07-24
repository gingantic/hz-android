package com.rhnxdev.hzplayer.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress

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
        val ip = "192.168.254.254"
        val resolved = NetworkDomainUtils.resolveDomain(null, ip)
        assertEquals("192.168.254.254", resolved)
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

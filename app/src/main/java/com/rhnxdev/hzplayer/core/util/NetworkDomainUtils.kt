package com.rhnxdev.hzplayer.core.util

import android.util.Log
import java.net.InetAddress

object NetworkDomainUtils {
    private const val TAG = "NetworkDomainUtils"

    /**
     * Attempts to resolve the domain (hostname / DNS domain / mDNS name) for a given host or [InetAddress].
     * If the domain can be resolved and is not a numeric IP address, returns the resolved domain name.
     * Otherwise returns the original [host].
     */
    fun resolveDomain(inetAddress: InetAddress? = null, host: String): String {
        val trimmedHost = host.trim()
        if (trimmedHost.isBlank()) return trimmedHost

        // If it's already a domain name (not numeric IP), return it as is
        if (!isNumericIp(trimmedHost)) {
            return trimmedHost
        }

        try {
            val addr = inetAddress ?: InetAddress.getByName(trimmedHost)

            // 1. Try hostName from InetAddress (DNS / mDNS reverse lookup)
            val hostName = addr.hostName
            if (!hostName.isNullOrBlank() && hostName != trimmedHost && !isNumericIp(hostName)) {
                Log.d(TAG, "resolveDomain: resolved $trimmedHost -> $hostName via hostName")
                return hostName
            }

            // 2. Try canonicalHostName (FQDN)
            val canonicalHost = addr.canonicalHostName
            if (!canonicalHost.isNullOrBlank() && canonicalHost != trimmedHost && !isNumericIp(canonicalHost)) {
                Log.d(TAG, "resolveDomain: resolved $trimmedHost -> $canonicalHost via canonicalHostName")
                return canonicalHost
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveDomain: failed to resolve domain for $trimmedHost: ${e.message}")
        }

        return trimmedHost
    }

    /**
     * Checks if a string is a numerical IPv4 or IPv6 address.
     */
    fun isNumericIp(host: String): Boolean {
        val clean = host.removeSurrounding("[", "]").trim()
        if (clean.isBlank()) return false

        // IPv6 contains colons
        if (clean.contains(":")) return true

        // IPv4 contains 4 octets of digits separated by dots
        val parts = clean.split(".")
        if (parts.size == 4 && parts.all { part -> part.isNotEmpty() && part.all { it.isDigit() } }) {
            return true
        }

        return false
    }
}

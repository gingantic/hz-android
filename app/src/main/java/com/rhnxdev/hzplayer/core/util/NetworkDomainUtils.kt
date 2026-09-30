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
     * Verify a candidate hostname is usable in place of a numeric IP by confirming it resolves
     * back to [expectedIp]. Tries the bare [candidate] first, then `candidate.local` (mDNS) for
     * a dot-less NetBIOS-style name. Returns the first form that resolves to [expectedIp], or null.
     *
     * Why the round-trip check: a NetBIOS/reverse-DNS name is worthless if the device can't later
     * connect to it, so we only swap in a name we just proved reachable.
     */
    fun verifiedHostName(candidate: String, expectedIp: String): String? {
        val name = candidate.trim()
        if (name.isBlank() || isNumericIp(name)) return null

        val forms = if (name.contains(".")) listOf(name) else listOf(name, "$name.local")
        for (form in forms) {
            try {
                val resolved = InetAddress.getByName(form)
                if (resolved.hostAddress == expectedIp) {
                    Log.d(TAG, "verifiedHostName: $form resolves to $expectedIp")
                    return form
                }
            } catch (e: Exception) {
                Log.d(TAG, "verifiedHostName: $form did not resolve: ${e.message}")
            }
        }
        return null
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

/**
 * Drop a leading `http://` / `https://` so the URL reads as host + path in a
 * compact UI (tab title, address bar, history row). Case-insensitive; any other
 * scheme (`about:`, `file:`, `content:`) passes through untouched.
 */
fun String.withoutScheme(): String = when {
    startsWith("https://", ignoreCase = true) -> substring(8)
    startsWith("http://", ignoreCase = true) -> substring(7)
    else -> this
}

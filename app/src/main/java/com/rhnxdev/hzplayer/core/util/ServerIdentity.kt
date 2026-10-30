package com.rhnxdev.hzplayer.core.util

/**
 * Stable machine identity used to recognise a saved server after its IP changes.
 *
 * The identity is the machine's name (mDNS service instance, NetBIOS or reverse-DNS
 * computer name), normalised so `NAS`, `nas.local` and `NAS.lan.` compare equal.
 * An IP address is never an identity, since that is exactly what changes.
 */
object ServerIdentity {

    /** mDNS appends " (2)" when two instances on the network share a name. */
    private val CONFLICT_SUFFIX = Regex("""\s*\(\d+\)$""")

    /**
     * Normalise [raw] to a comparable identity.
     *
     * @param raw — mDNS instance name, NetBIOS name, or (reverse) DNS name.
     * @return the lowercase first DNS label, or null when blank or a numeric IP.
     */
    fun normalize(raw: String?): String? {
        val trimmed = raw?.trim().orEmpty()
        if (trimmed.isEmpty() || NetworkDomainUtils.isNumericIp(trimmed)) return null
        val label = trimmed
            .trimEnd('.')
            .replace(CONFLICT_SUFFIX, "")
            .substringBefore('.')
            .trim()
            .lowercase()
        return label.ifEmpty { null }
    }

    /** True when both names normalise to the same non-null identity. */
    fun sameServer(a: String?, b: String?): Boolean {
        val left = normalize(a) ?: return false
        return left == normalize(b)
    }
}

package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.net.Inet4Address
import java.net.InetAddress
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

private const val TAG = "ServerRelocator"

/** mDNS service types a machine may advertise; any of them identifies the machine by name. */
private val MDNS_SERVICE_TYPES = listOf("_smb._tcp", "_ftp._tcp", "_webdav._tcp", "_sftp-ssh._tcp")

private const val LOCATE_TIMEOUT_MS = 10_000L
private const val MDNS_WINDOW_MS = 2_500L
private const val RESOLVE_TIMEOUT_MS = 2_000L
private const val PROBE_TIMEOUT_MS = 1_000
private const val SWEEP_CONCURRENCY = 128

/** Minimum gap between two searches for the same server, so repeated taps on an offline one stay cheap. */
private const val COOLDOWN_MS = 60_000L

/**
 * A server's current address.
 *
 * @param ip — numeric IPv4 the machine answered at.
 * @param host — value to save as the connect target: a name that resolves to [ip] when one
 *   exists (survives future IP changes), otherwise [ip].
 */
data class LocatedServer(val ip: String, val host: String)

/**
 * A machine's LAN identity.
 *
 * @param name — normalised machine name (see [ServerIdentity]).
 * @param ip — private-LAN IPv4 it answered at.
 */
data class MachineIdentity(val name: String, val ip: String)

/**
 * Finds a saved server again after its IP changed.
 *
 * The machine is matched by name ([ServerConfig.discoveryName]) using mDNS and a subnet
 * sweep in parallel. Every host that matches the name, across both searches, must be one
 * single IPv4 address that accepts a connection on the saved port. Zero or several distinct
 * addresses return null, so a second machine claiming the same name can never silently win.
 */
@Singleton
class ServerRelocator @Inject constructor(
    @ApplicationContext private val context: Context,
    private val discoverer: ServerDiscoverer,
) {
    private val nsdManager = context.getSystemService(Context.NSD_SERVICE) as NsdManager
    private val wifiManager = context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val sweepThrottle = Semaphore(SWEEP_CONCURRENCY)
    private val lastAttemptAt = ConcurrentHashMap<Long, Long>()

    /**
     * Search the local network for [server].
     *
     * @return the new address, or null when the server has no identity, the device is off the
     *   LAN, a search ran for it within the last minute, nothing matched, or the match was ambiguous.
     */
    suspend fun locate(server: ServerConfig): LocatedServer? {
        val identity = ServerIdentity.normalize(server.discoveryName) ?: return null
        if (!discoverer.isOnCompatibleNetwork.value) {
            Log.d(TAG, "locate: not on Wi-Fi/Ethernet, skipping")
            return null
        }
        if (!claimAttempt(server.id)) {
            Log.d(TAG, "locate: searched recently for server ${server.id}, skipping")
            return null
        }
        return withContext(Dispatchers.IO) {
            val candidates = withTimeoutOrNull(LOCATE_TIMEOUT_MS) { collectCandidates(server, identity) }
                ?: emptySet()
            when (candidates.size) {
                0 -> null.also { Log.d(TAG, "locate: no match for '$identity'") }
                1 -> {
                    val ip = candidates.first()
                    LocatedServer(ip, NetworkDomainUtils.verifiedHostName(identity, ip) ?: ip)
                        .also { Log.d(TAG, "locate: '$identity' is now at ${it.ip} (host=${it.host})") }
                }
                else -> null.also { Log.w(TAG, "locate: '$identity' matched ${candidates.size} hosts, refusing") }
            }
        }
    }

    /**
     * Learn the machine name behind [host] so the server can be found again later.
     *
     * Only private-LAN IPv4 addresses are probed: a public server has no LAN identity worth
     * saving and must not receive NetBIOS queries.
     *
     * @param host — saved connect target: a numeric IP or a resolvable name.
     * @return the machine's name and IPv4, or null when it is off-LAN or reports no name.
     */
    suspend fun identify(host: String): MachineIdentity? = withContext(Dispatchers.IO) {
        val ip = resolveLanIpv4(host) ?: return@withContext null
        val name = ServerIdentity.normalize(discoverer.resolveComputerName(ip))
            ?: return@withContext null
        MachineIdentity(name, ip)
    }

    /** IPv4 for [host] when it is a private-LAN address, else null. */
    private fun resolveLanIpv4(host: String): String? {
        val address = try {
            if (NetworkDomainUtils.isNumericIp(host)) {
                if (host.contains(":")) return null
                InetAddress.getByName(host)
            } else {
                InetAddress.getAllByName(host).firstOrNull { it is Inet4Address }
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveLanIpv4: cannot resolve '$host': ${e.message}")
            null
        }
        return (address as? Inet4Address)?.takeIf { it.isSiteLocalAddress }?.hostAddress
    }

    private fun claimAttempt(serverId: Long): Boolean {
        val now = SystemClock.elapsedRealtime()
        val previous = lastAttemptAt[serverId]
        if (previous != null && now - previous < COOLDOWN_MS) return false
        lastAttemptAt[serverId] = now
        return true
    }

    private suspend fun collectCandidates(server: ServerConfig, identity: String): Set<String> =
        coroutineScope {
            val found = ConcurrentHashMap.newKeySet<String>()
            val mdns = launch { found.addAll(findViaMdns(server, identity)) }
            val sweep = launch { found.addAll(findViaSweep(server, identity)) }
            joinAll(mdns, sweep)
            found
        }

    // ── Strategy A: mDNS ───────────────────────────────────────────────

    private suspend fun findViaMdns(server: ServerConfig, identity: String): Set<String> {
        if (!discoverer.hasNearbyWifiPermission()) return emptySet()
        val ips = ConcurrentHashMap.newKeySet<String>()
        // Android 12 and below only receive mDNS multicast while a lock is held.
        val lock = runCatching {
            wifiManager.createMulticastLock("ServerRelocatorMulticastLock").apply {
                setReferenceCounted(false)
                acquire()
            }
        }.getOrNull()
        try {
            coroutineScope {
                val started = mutableListOf<NsdManager.DiscoveryListener>()
                try {
                    MDNS_SERVICE_TYPES.forEach { type ->
                        val listener = discoveryListener(identity, this, ips)
                        try {
                            nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                            started += listener
                        } catch (e: Exception) {
                            Log.w(TAG, "findViaMdns: discoverServices failed for $type: ${e.message}")
                        }
                    }
                    delay(MDNS_WINDOW_MS)
                } finally {
                    started.forEach { runCatching { nsdManager.stopServiceDiscovery(it) } }
                }
            }
        } finally {
            runCatching { if (lock?.isHeld == true) lock.release() }
        }
        return ips.filter { discoverer.isPortOpen(it, server.port, PROBE_TIMEOUT_MS) }.toSet()
    }

    private fun discoveryListener(
        identity: String,
        scope: CoroutineScope,
        ips: MutableSet<String>,
    ) = object : NsdManager.DiscoveryListener {
        override fun onServiceFound(info: NsdServiceInfo) {
            if (!ServerIdentity.sameServer(info.serviceName, identity)) return
            scope.launch {
                val resolved = resolveOrNull(info) ?: return@launch
                ipv4Of(resolved)?.let { ips.add(it) }
            }
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.w(TAG, "mDNS start failed: type=$serviceType error=$errorCode")
        }
        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {}
        override fun onDiscoveryStarted(serviceType: String) {}
        override fun onDiscoveryStopped(serviceType: String) {}
        override fun onServiceLost(serviceInfo: NsdServiceInfo) {}
    }

    private suspend fun resolveOrNull(info: NsdServiceInfo): NsdServiceInfo? = try {
        withTimeoutOrNull(RESOLVE_TIMEOUT_MS) { discoverer.resolveServiceDeferred(info) }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.d(TAG, "resolve failed for ${info.serviceName}: ${e.message}")
        null
    }

    /** First usable IPv4 of a resolved service. IPv6 and link-local addresses are ignored. */
    @Suppress("DEPRECATION")
    private fun ipv4Of(info: NsdServiceInfo): String? {
        val addresses = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            info.hostAddresses
        } else {
            listOfNotNull(info.host)
        }
        return addresses.filterIsInstance<Inet4Address>()
            .firstOrNull { !it.isLoopbackAddress && !it.isLinkLocalAddress }
            ?.hostAddress
    }

    // ── Strategy B: subnet sweep ───────────────────────────────────────

    /** Probe the saved port across the current /24, then check each responder's machine name. */
    private suspend fun findViaSweep(server: ServerConfig, identity: String): Set<String> {
        val self = discoverer.getActiveNetworkInetAddress()?.hostAddress
        if (self.isNullOrEmpty() || !self.contains(".")) return emptySet()
        val prefix = self.substringBeforeLast(".")
        val matches = ConcurrentHashMap.newKeySet<String>()
        coroutineScope {
            for (octet in 1..254) {
                val ip = "$prefix.$octet"
                if (ip == self) continue
                sweepThrottle.acquire()
                launch(Dispatchers.IO) {
                    try {
                        if (discoverer.isPortOpen(ip, server.port, PROBE_TIMEOUT_MS) &&
                            ServerIdentity.sameServer(discoverer.resolveComputerName(ip), identity)
                        ) {
                            matches.add(ip)
                        }
                    } finally {
                        sweepThrottle.release()
                    }
                }
            }
        }
        return matches
    }
}

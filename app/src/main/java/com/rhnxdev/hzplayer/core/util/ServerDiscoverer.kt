package com.rhnxdev.hzplayer.core.util

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkCapabilities.TRANSPORT_ETHERNET
import android.net.NetworkCapabilities.TRANSPORT_WIFI
import android.net.NetworkRequest
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.net.wifi.WifiManager
import android.os.Build
import android.util.Log
import com.rhnxdev.hzplayer.domain.model.NetworkProtocol
import com.rhnxdev.hzplayer.domain.model.ServerConfig
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.suspendCancellableCoroutine
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.Socket
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

private const val TAG = "ServerDiscoverer"

private val DISCOVERY_SERVICE_TYPES = listOf(
    "_smb._tcp",
    "_ftp._tcp",
    "_webdav._tcp",
)

private fun serviceTypeToProtocol(type: String): NetworkProtocol = when {
    type.contains("_smb") -> NetworkProtocol.SMB
    type.contains("_ftp") -> NetworkProtocol.FTP
    type.contains("_webdav") -> NetworkProtocol.WEBDAV
    else -> NetworkProtocol.FTP
}

/** ponytail: concrete class, no interface. Add interface if test mocking needed. */
@Singleton
class ServerDiscoverer @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    companion object {
        /** Permission required for NSD/mDNS discovery on Android 13+ (API 33). */
        const val NEARBY_WIFI_PERMISSION = android.Manifest.permission.NEARBY_WIFI_DEVICES
    }

    private val connectivityManager: ConnectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
    private val wifiManager: WifiManager =
        context.getSystemService(Context.WIFI_SERVICE) as WifiManager
    private val nsdManager: NsdManager =
        context.getSystemService(Context.NSD_SERVICE) as NsdManager

    private val _discoveredServers = MutableStateFlow<List<ServerConfig>>(emptyList())
    val discoveredServers: StateFlow<List<ServerConfig>> = _discoveredServers.asStateFlow()

    private val _isScanning = MutableStateFlow(false)
    val isScanning: StateFlow<Boolean> = _isScanning.asStateFlow()

    private val _isOnCompatibleNetwork = MutableStateFlow(checkCompat(connectivityManager))
    val isOnCompatibleNetwork: StateFlow<Boolean> = _isOnCompatibleNetwork.asStateFlow()

    private val discoveryScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val networkCallback = createNetworkCallback()
    private var registerNetworkCalled = false
    private val discoveredNames = ConcurrentHashMap.newKeySet<String>()
    private var multicastLock: WifiManager.MulticastLock? = null

    // Map of serviceType to its active DiscoveryListener
    private val activeListeners = mutableMapOf<String, NsdManager.DiscoveryListener>()
    private val resolveMutex = Mutex()
    private val subnetProbeThrottle = Semaphore(64)
    /** Tracks the in-flight subnet scan so a new scan cancels the previous one. */
    private var subnetScanJob: kotlinx.coroutines.Job? = null

    init {
        Log.d(TAG, "ServerDiscoverer constructor: registering network callback")
        val request = NetworkRequest.Builder()
            .addTransportType(TRANSPORT_WIFI)
            .addTransportType(TRANSPORT_ETHERNET)
            .build()
        connectivityManager.registerNetworkCallback(request, networkCallback)
        registerNetworkCalled = true
    }

    // ── Public API ─────────────────────────────────────────────────────

    /** True when NSD discovery is permitted (always true below API 33). */
    fun hasNearbyWifiPermission(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            context.checkSelfPermission(NEARBY_WIFI_PERMISSION) == PackageManager.PERMISSION_GRANTED

    fun startScan() {
        if (_isScanning.value) { Log.d(TAG, "startScan: already scanning"); return }
        _discoveredServers.value = emptyList()
        _isScanning.value = true
        discoveredNames.clear()
        Log.d(TAG, "startScan: beginning NsdManager discovery for $DISCOVERY_SERVICE_TYPES")

        // Acquire multicast lock for network-wide packet discovery (just in case)
        try {
            val lock = wifiManager.createMulticastLock("ServerDiscovererMulticastLock")
            lock.setReferenceCounted(false)
            multicastLock = lock
            lock.acquire()
            Log.d(TAG, "startScan: acquired multicast lock")
        } catch (e: SecurityException) {
            Log.e(TAG, "startScan: security exception acquiring multicast lock", e)
        } catch (e: Exception) {
            Log.e(TAG, "startScan: failed to acquire multicast lock", e)
        }

        // Start native NsdManager discovery — requires NEARBY_WIFI_DEVICES on API 33+.
        if (hasNearbyWifiPermission()) {
            DISCOVERY_SERVICE_TYPES.forEach { type ->
                val listener = createDiscoveryListener(type)
                activeListeners[type] = listener
                try {
                    nsdManager.discoverServices(type, NsdManager.PROTOCOL_DNS_SD, listener)
                    Log.d(TAG, "startScan: discoverServices started for $type")
                } catch (e: Exception) {
                    Log.e(TAG, "startScan: discoverServices failed for $type", e)
                }
            }
        } else {
            Log.w(TAG, "startScan: NEARBY_WIFI_DEVICES not granted (API 33+); " +
                "NSD skipped — subnet scan fallback still runs")
        }

        // Launch fallback subnet port scanning to discover servers not advertising via mDNS.
        // Guard so a second startScan (after stopScan) cancels the prior in-flight scan
        // instead of stacking another /24 of probes.
        if (subnetScanJob?.isActive != true) {
            subnetScanJob = discoveryScope.launch {
                val address = getActiveNetworkInetAddress()
                if (address == null) {
                    Log.w(TAG, "startScan: no active network address, subnet scan skipped")
                } else {
                    val ipStr = address.hostAddress
                    if (!ipStr.isNullOrEmpty() && ipStr.contains(".")) {
                        startSubnetScan(ipStr)
                    }
                }
            }
        }
    }

    fun stopScan() {
        if (!_isScanning.value) return
        Log.d(TAG, "stopScan")
        _isScanning.value = false

        // Cancel any in-flight subnet scan so its probes don't keep running.
        subnetScanJob?.cancel()
        subnetScanJob = null

        // Unregister all active listeners
        activeListeners.forEach { (type, listener) ->
            try {
                nsdManager.stopServiceDiscovery(listener)
                Log.d(TAG, "stopScan: stopped discovery for $type")
            } catch (e: Exception) {
                Log.e(TAG, "stopScan: failed to stop discovery for $type", e)
            }
        }
        activeListeners.clear()

        // Release multicast lock to conserve battery
        try {
            multicastLock?.let {
                if (it.isHeld) {
                    it.release()
                    Log.d(TAG, "stopScan: released multicast lock")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "stopScan: failed to release multicast lock", e)
        }
        multicastLock = null
    }

    fun dismissDiscoveredServer(host: String) {
        _discoveredServers.value = _discoveredServers.value.filter { it.host != host }
    }

    fun cleanup() {
        // Per-screen exit: only stop the active scan. ServerDiscoverer is a
        // @Singleton, so cancelling discoveryScope or unregistering the network
        // callback here would break discovery for the rest of the process.
        stopScan()
        runCatching { connectivityManager.unregisterNetworkCallback(networkCallback) }
    }

    // ── Subnet Port Scanner Fallback ───────────────────────────────────

    private fun startSubnetScan(baseIp: String) {
        val prefix = baseIp.substringBeforeLast(".")
        Log.d(TAG, "startSubnetScan: starting parallel subnet probe on $prefix.1 - $prefix.254")
        
        discoveryScope.launch {
            // Launch parallel probes for each host IP on a /24 subnet
            for (lastOctet in 1..254) {
                val host = "$prefix.$lastOctet"
                if (host == baseIp) continue // skip self
                
                subnetProbeThrottle.acquire()
                launch(Dispatchers.IO) {
                    try {
                        probeHost(host)
                    } finally {
                        subnetProbeThrottle.release()
                    }
                }
            }
        }
    }

    private fun resolveComputerName(host: String): String? {
        // 1. Try Reverse DNS
        try {
            val inetAddress = InetAddress.getByName(host)
            val dnsHost = inetAddress.hostName
            if (!dnsHost.isNullOrEmpty() && dnsHost != host) {
                val cleanName = dnsHost.substringBefore(".local").substringBefore(".")
                Log.d(TAG, "resolveComputerName: DNS resolved $host to $cleanName")
                return cleanName
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveComputerName: DNS reverse lookup failed for $host: ${e.message}")
        }

        // 2. Try NetBIOS Name Query (UDP 137 Node Status query)
        val requestBytes = byteArrayOf(
            0xa2.toByte(), 0x48.toByte(), // Transaction ID
            0x00.toByte(), 0x00.toByte(), // Flags (query)
            0x00.toByte(), 0x01.toByte(), // Questions
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x00.toByte(), 0x00.toByte(),
            0x20.toByte(), // length of next label (32)
            0x43.toByte(), 0x4b.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(), 0x41.toByte(),
            0x41.toByte(), 0x41.toByte(), 0x00.toByte(), 0x00.toByte(), 0x21.toByte(), // Type: NBSTAT
            0x00.toByte(), 0x01.toByte()  // Class: IN
        )
        
        try {
            java.net.DatagramSocket().use { socket ->
                socket.soTimeout = 1000
                val address = InetAddress.getByName(host)
                val packet = java.net.DatagramPacket(requestBytes, requestBytes.size, address, 137)
                socket.send(packet)
                
                val buffer = ByteArray(1024)
                val response = java.net.DatagramPacket(buffer, buffer.size)
                socket.receive(response)
                
                val length = response.length
                // Scan the response for the type/class signature 0x00 0x21 0x00 0x01 to find names data block
                for (i in 12 until (length - 26)) {
                    if (buffer[i] == 0x00.toByte() && buffer[i + 1] == 0x21.toByte() &&
                        buffer[i + 2] == 0x00.toByte() && buffer[i + 3] == 0x01.toByte()
                    ) {
                        val numNames = buffer[i + 10].toInt() and 0xFF
                        if (numNames > 0 && length >= i + 11 + 15) {
                            val nameBytes = ByteArray(15)
                            System.arraycopy(buffer, i + 11, nameBytes, 0, 15)
                            val nameStr = String(nameBytes, Charsets.US_ASCII).trim()
                            if (nameStr.isNotEmpty() && nameStr.all { it.isLetterOrDigit() || it == '-' || it == '_' }) {
                                Log.d(TAG, "resolveComputerName: NetBIOS resolved $host to $nameStr")
                                return nameStr
                            }
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.d(TAG, "resolveComputerName: NetBIOS UDP query failed for $host: ${e.message}")
        }

        return null
    }

    private fun probeHost(host: String) {
        // Probe SMB (445)
        if (isPortOpen(host, 445, 1200)) {
            Log.d(TAG, "probeHost: discovered active SMB service on $host:445")
            val resolvedName = resolveComputerName(host)
            val displayName = if (!resolvedName.isNullOrEmpty()) resolvedName else host
            addDiscoveredServer(
                name = "SMB ($displayName)",
                protocol = NetworkProtocol.SMB,
                host = host,
                port = 445
            )
        }
        // Probe FTP (21)
        if (isPortOpen(host, 21, 1200)) {
            Log.d(TAG, "probeHost: discovered active FTP service on $host:21")
            val resolvedName = resolveComputerName(host)
            val displayName = if (!resolvedName.isNullOrEmpty()) resolvedName else host
            addDiscoveredServer(
                name = "FTP ($displayName)",
                protocol = NetworkProtocol.FTP,
                host = host,
                port = 21
            )
        }
    }

    private fun isPortOpen(host: String, port: Int, timeoutMs: Int): Boolean {
        return try {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
                true
            }
        } catch (e: Exception) {
            false
        }
    }

    private fun addDiscoveredServer(name: String, protocol: NetworkProtocol, host: String, port: Int) {
        _discoveredServers.update { current ->
            val existing = current.any { it.host == host && it.protocol == protocol }
            if (existing) return@update current
            Log.d(TAG, "addDiscoveredServer: adding $name ($protocol @ $host:$port)")
            current + ServerConfig(
                name = name,
                protocol = protocol,
                host = host,
                port = port,
                basePath = "/",
            )
        }
    }

    // ── Network detection ──────────────────────────────────────────────

    private fun getActiveNetworkInetAddress(): InetAddress? {
        try {
            val activeNetwork = connectivityManager.activeNetwork
            if (activeNetwork != null) {
                val linkProperties = connectivityManager.getLinkProperties(activeNetwork)
                if (linkProperties != null) {
                    for (linkAddress in linkProperties.linkAddresses) {
                        val address = linkAddress.address
                        if (address is Inet4Address && !address.isLoopbackAddress) {
                            Log.d(TAG, "getActiveNetworkInetAddress: found IPv4 address ${address.hostAddress} on active network")
                            return address
                        }
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "getActiveNetworkInetAddress: error", e)
        }
        // Fallback to wifiManager dhcpInfo if activeNetwork lookup fails
        return getWifiInetAddress()
    }

    private fun getWifiInetAddress(): InetAddress? {
        return try {
            val dhcpInfo = wifiManager.dhcpInfo ?: return null.also {
                Log.w(TAG, "getWifiInetAddress: dhcpInfo null")
            }
            val ipInt = dhcpInfo.ipAddress
            if (ipInt == 0) return null.also { Log.w(TAG, "getWifiInetAddress: ipAddress 0") }
            val ipBytes = byteArrayOf(
                (ipInt and 0xFF).toByte(),
                (ipInt shr 8 and 0xFF).toByte(),
                (ipInt shr 16 and 0xFF).toByte(),
                (ipInt shr 24 and 0xFF).toByte(),
            )
            InetAddress.getByAddress(ipBytes).also {
                Log.d(TAG, "getWifiInetAddress: ${it.hostAddress}")
            }
        } catch (e: SecurityException) {
            Log.e(TAG, "getWifiInetAddress: no ACCESS_WIFI_STATE permission", e)
            null
        } catch (e: Exception) {
            Log.e(TAG, "getWifiInetAddress: error", e)
            null
        }
    }

    private fun createNetworkCallback() = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) { updateCompat() }
        override fun onLost(network: Network) { updateCompat() }
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { updateCompat() }
    }

    private fun updateCompat() {
        _isOnCompatibleNetwork.value = checkCompat(connectivityManager)
    }

    private fun checkCompat(cm: ConnectivityManager): Boolean {
        val active = cm.activeNetwork ?: return false.also {
            Log.d(TAG, "checkCompat: no active network")
        }
        val caps = cm.getNetworkCapabilities(active) ?: return false.also {
            Log.d(TAG, "checkCompat: no capabilities")
        }
        val result = caps.hasTransport(TRANSPORT_WIFI) || caps.hasTransport(TRANSPORT_ETHERNET)
        Log.d(TAG, "checkCompat: hasTransport(WIFI|ETHERNET)=$result")
        return result
    }

    // ── Discovery ──────────────────────────────────────────────────────

    private fun createDiscoveryListener(serviceType: String) = object : NsdManager.DiscoveryListener {
        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "onStartDiscoveryFailed: type=$serviceType, error=$errorCode")
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.e(TAG, "onStopDiscoveryFailed: type=$serviceType, error=$errorCode")
        }

        override fun onDiscoveryStarted(serviceType: String) {
        }

        override fun onDiscoveryStopped(serviceType: String) {
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            if (name.isBlank() || discoveredNames.contains(name)) {
                return
            }
            discoveredNames.add(name)

            // Resolve service info sequentially to avoid NsdManager.FAILURE_ALREADY_ACTIVE
            discoveryScope.launch {
                try {
                    val resolvedInfo = resolveServiceDeferred(serviceInfo)
                    onServiceResolved(resolvedInfo)
                } catch (e: Exception) {
                    Log.e(TAG, "onServiceFound: resolve failed for $name: ${e.message}")
                    discoveredNames.remove(name)
                }
            }
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            val name = serviceInfo.serviceName
            discoveredNames.remove(name)
            
            // Remove from uiState
            val existing = _discoveredServers.value.filter { it.name == name }
            existing.forEach { dismissDiscoveredServer(it.host) }
        }
    }

    private suspend fun resolveServiceDeferred(serviceInfo: NsdServiceInfo): NsdServiceInfo = resolveMutex.withLock {
        suspendCancellableCoroutine { continuation ->
            val resolveListener = object : NsdManager.ResolveListener {
                override fun onResolveFailed(info: NsdServiceInfo, errorCode: Int) {
                    if (continuation.isActive) {
                        continuation.resumeWith(Result.failure(Exception("Resolve failed with error code $errorCode")))
                    }
                }

                override fun onServiceResolved(info: NsdServiceInfo) {
                    if (continuation.isActive) {
                        continuation.resume(info)
                    }
                }
            }

            try {
                nsdManager.resolveService(serviceInfo, resolveListener)
            } catch (e: Exception) {
                if (continuation.isActive) {
                    continuation.resumeWithException(e)
                }
            }
        }
    }

    private fun onServiceResolved(info: NsdServiceInfo) {
        val name = info.serviceName
        val hostAddress = info.host?.hostAddress?.let {
            if (it.contains("%")) it.substringBefore("%") else it
        }
        val port = info.port
        val type = info.serviceType ?: ""

        if (hostAddress.isNullOrEmpty()) {
            Log.w(TAG, "onServiceResolved: no IP address for $name")
            return
        }

        val displayName = name.removeSuffix(".$type").removeSuffix(".")

        addDiscoveredServer(
            name = displayName,
            protocol = serviceTypeToProtocol(type),
            host = hostAddress,
            port = port
        )
    }
}

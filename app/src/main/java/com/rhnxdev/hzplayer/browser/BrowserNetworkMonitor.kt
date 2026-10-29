package com.rhnxdev.hzplayer.browser

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * What the browser needs to know about the connection.
 *
 * @param online — the default network is connected and validated (has working internet)
 * @param constrained — online, but slow or restricted by Data Saver, so heavy content should wait
 */
data class BrowserNetworkState(
    val online: Boolean = true,
    val constrained: Boolean = false,
)

/**
 * Downstream estimates below this are treated as slow. The value is the
 * platform's own estimate from the radio, not a measurement, so the threshold
 * only needs to separate 2G/EDGE-class links from everything else.
 */
private const val SLOW_LINK_KBPS = 1500

/**
 * Pure classification of a network's properties.
 *
 * @param downstreamKbps — `NetworkCapabilities.getLinkDownstreamBandwidthKbps()`, 0 when unknown
 * @param metered — the network is not marked unmetered
 * @param dataSaver — the user enabled Android's Data Saver
 */
internal fun classifyNetwork(
    hasInternet: Boolean,
    validated: Boolean,
    downstreamKbps: Int,
    metered: Boolean,
    dataSaver: Boolean,
): BrowserNetworkState {
    val online = hasInternet && validated
    if (!online) return BrowserNetworkState(online = false, constrained = false)
    val slow = downstreamKbps in 1 until SLOW_LINK_KBPS
    return BrowserNetworkState(online = true, constrained = slow || (metered && dataSaver))
}

/**
 * Tracks the default network and publishes it as [state]. Callbacks are
 * delivered on the main thread, so collectors can touch WebViews directly.
 * Call [start] once and [stop] when the owner is cleared.
 */
class BrowserNetworkMonitor(context: Context) {

    private val connectivity =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _state = MutableStateFlow(readCurrent())
    val state: StateFlow<BrowserNetworkState> = _state.asStateFlow()

    private var callback: ConnectivityManager.NetworkCallback? = null

    /** True when the active network is metered (billed by data). Read at call time. */
    val isMetered: Boolean
        get() = connectivity?.isActiveNetworkMetered == true

    fun start() {
        val cm = connectivity ?: return
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _state.value = classify(caps)
            }

            override fun onLost(network: Network) {
                _state.value = BrowserNetworkState(online = false)
            }
        }
        // Some OEM builds throw when the per-app callback quota is exhausted;
        // adaptive loading is a nicety, so fall back to the one-shot snapshot.
        runCatching { cm.registerDefaultNetworkCallback(cb, Handler(Looper.getMainLooper())) }
            .onSuccess { callback = cb }
    }

    fun stop() {
        val cb = callback ?: return
        callback = null
        runCatching { connectivity?.unregisterNetworkCallback(cb) }
    }

    private fun readCurrent(): BrowserNetworkState {
        val cm = connectivity ?: return BrowserNetworkState()
        val caps = cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
            ?: return BrowserNetworkState(online = false)
        return classify(caps)
    }

    private fun classify(caps: NetworkCapabilities): BrowserNetworkState = classifyNetwork(
        hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET),
        validated = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED),
        downstreamKbps = caps.linkDownstreamBandwidthKbps,
        metered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED),
        dataSaver = connectivity?.restrictBackgroundStatus ==
            ConnectivityManager.RESTRICT_BACKGROUND_STATUS_ENABLED,
    )
}

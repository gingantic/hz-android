package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.core.util.bitsToHuman
import com.rhnxdev.hzplayer.core.util.formatBitsPerSecond
import com.rhnxdev.hzplayer.core.util.formatDebugBytes
import com.rhnxdev.hzplayer.core.util.formatDebugSpeed
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the "stats for nerds" debug overlay: the debug-mode preference observer
 * and the 1 Hz polling loop that fills [PlayerUiState.debugStats].
 *
 * Split out of [PlayerViewModel] purely to shrink it — behaviour is unchanged.
 */
internal class PlayerDebugController(
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    private var debugPollJob: Job? = null

    fun observe() {
        scope.launch {
            userPreferencesRepository.debugMode.collect { enabled ->
                uiState.update { it.copy(debugMode = enabled) }
                if (enabled && uiState.value.debugOverlayVisible) startDebugPolling()
                else stopDebugPolling()
            }
        }
    }

    fun onToggleDebugOverlay() {
        val show = !uiState.value.debugOverlayVisible
        uiState.update { it.copy(debugOverlayVisible = show) }
        if (show && uiState.value.debugMode) startDebugPolling()
        else stopDebugPolling()
    }

    private fun startDebugPolling() {
        debugPollJob?.cancel()
        debugPollJob = scope.launch {
            while (isActive) {
                try {
                    val stats = playerRepository.getDebugStats() ?: DebugStats()
                    val nt = uiState.value.networkTraffic
                    val speedDown = nt.speedDown
                    val duration = uiState.value.duration
                    // compute avg bitrate from total bytes / duration when fmt.bitrate is 0
                    val totalBytes = nt.bytesDown
                    val videoBitrate = if (stats.videoBitrate.isEmpty() && duration > 0 && totalBytes > 0) {
                        formatBitsPerSecond(((totalBytes * 8) / (duration / 1000)).toLong())
                    } else if (stats.videoBitrate.isNotEmpty()) {
                        bitsToHuman(stats.videoBitrate)
                    } else ""
                    val audioBitrate = if (stats.audioBitrate.isNotEmpty()) bitsToHuman(stats.audioBitrate) else ""
                    uiState.update { it.copy(
                        debugStats = stats.copy(
                            videoBitrateEstimated = videoBitrate,
                            audioBitrateEstimated = audioBitrate,
                            bufferedPct = it.bufferedPercentage,
                            networkSpeed = if (speedDown > 0) formatDebugSpeed(speedDown) else "",
                            bytesDownloaded = if (totalBytes > 0) formatDebugBytes(totalBytes) else "",
                            isVisible = true,
                        )
                    ) }
                } catch (_: Exception) { }
                delay(1000)
            }
        }
    }

    private fun stopDebugPolling() {
        debugPollJob?.cancel()
        debugPollJob = null
        uiState.update { it.copy(debugStats = DebugStats()) }
    }
}

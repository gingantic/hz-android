package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * Owns the high-frequency playback position tick, seek bookkeeping, and periodic
 * resume-progress persistence.
 *
 * The 250 ms position is exposed on [position] — a channel separate from the main
 * UI state — so the tick only recomposes the seek bar, not the whole player.
 *
 * Split out of [PlayerViewModel] purely to shrink it — behaviour is unchanged.
 */
internal class PlayerPositionController(
    private val scope: CoroutineScope,
    private val playerRepository: PlayerRepository,
    private val resumeProgress: ResumeRepository,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    /**
     * High-frequency playback position (ms). Emitted every 250 ms by
     * [startPositionUpdates]. Kept separate from the UI state so the 250 ms tick
     * only recomposes the seek bar, not the entire player UI.
     */
    private val _position = MutableStateFlow(0L)
    val position: StateFlow<Long> = _position.asStateFlow()

    private var positionUpdateJob: Job? = null

    // Outlives the ViewModel scope so a final save during onCleared() still completes.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveTick = 0

    private var isSeeking = false
    private var lastSeekTimestamp = 0L
    private var seekTargetPosition = 0L

    companion object {
        private const val SEEK_DEBOUNCE_MS = 150L
    }

    fun start() {
        positionUpdateJob?.cancel()
        positionUpdateJob = scope.launch {
            while (isActive) {
                delay(250)
                val engine = playerRepository.activeEngine
                val duration = engine.getDuration()
                val position = engine.getCurrentPosition()
                val bufferedPos = engine.getBufferedPosition()
                val bufferedPct = if (duration > 0) {
                    ((bufferedPos * 100) / duration).toInt().coerceIn(0, 100)
                } else 0
                val currentUri = playerRepository.currentPlaybackUri

                val effectivePosition = if (isSeeking) seekTargetPosition else position

                // Position flows on its own channel — see [_position] / [position].
                _position.value = effectivePosition

                uiState.update { state ->
                    val uriChanged = state.currentPlaybackUri != currentUri
                    if (uriChanged || state.duration != duration || state.bufferedPercentage != bufferedPct) {
                        state.copy(

                            duration = duration,
                            bufferedPercentage = bufferedPct,
                            currentPlaybackUri = currentUri,
                        )
                    } else state
                }

                // Persist progress every ~5s so a crash/kill can still resume.
                if (!isSeeking && ++saveTick >= 20) {
                    saveTick = 0
                    if (currentUri != null && position > 0 && duration > 0) {
                        saveScope.launch { resumeProgress.saveProgress(currentUri, position, duration) }
                    }
                }
            }
        }
    }

    /** Read engine position on the current (main) thread, persist off-thread. */
    fun saveProgressNow() {
        val uri = playerRepository.currentPlaybackUri ?: return
        val engine = playerRepository.activeEngine
        val pos = engine.getCurrentPosition()
        val dur = engine.getDuration()
        saveScope.launch { resumeProgress.saveProgress(uri, pos, dur) }
    }

    /** Clears the seeking flag once the engine settles out of BUFFERING. */
    fun onPlaybackState(state: PlayerState) {
        if (isSeeking && state != PlayerState.BUFFERING) {
            isSeeking = false
        }
    }

    fun onSeekTo(positionMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSeekTimestamp < SEEK_DEBOUNCE_MS) return
        markSeekStart(positionMs)
        playerRepository.seekTo(positionMs)
    }

    fun onSkipForward() {
        markSeekStart(position.value + 10_000)
        playerRepository.skipForward(10000)
    }

    fun onSkipBackward() {
        markSeekStart((position.value - 10_000).coerceAtLeast(0))
        playerRepository.skipBackward(10000)
    }

    fun onSeekBy(deltaMs: Long) {
        val target = (playerRepository.activeEngine.getCurrentPosition() + deltaMs).coerceAtLeast(0)
        markSeekStart(target)
        if (deltaMs >= 0) playerRepository.skipForward(deltaMs)
        else playerRepository.skipBackward(-deltaMs)
    }

    private fun markSeekStart(targetMs: Long) {
        isSeeking = true
        lastSeekTimestamp = System.currentTimeMillis()
        seekTargetPosition = targetMs.coerceAtLeast(0)
    }

    fun onCleared() {
        positionUpdateJob?.cancel()
        saveProgressNow()
        saveScope.cancel()
    }
}

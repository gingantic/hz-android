package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.data.repository.PlayerRepositoryImpl
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val playerRepositoryImpl: PlayerRepositoryImpl,
    private val userPreferencesRepository: UserPreferencesRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var positionUpdateJob: Job? = null

    // ── Seek state (Bug 1 + 6 fix) ──────────────────────────────

    /** True while ExoPlayer is settling after a seekTo(). */
    private var isSeeking = false

    /** Timestamp of the last seekTo() call. Suppresses position poller until elapsed. */
    private var lastSeekTimestamp = 0L

    /** The position we seeked to — used as optimistic UI value during buffering. */
    private var seekTargetPosition = 0L

    companion object {
        /** Minimum interval between consecutive seeks (ms). */
        private const val SEEK_DEBOUNCE_MS = 150L
    }

    /** Expose the active engine for VLC surface rendering. */
    fun getActiveEngine(): IPlayerEngine = playerRepository.activeEngine

    /** Expose the ExoPlayer instance for [PlayerView]. */
    fun getExoPlayer(): Player? = playerRepositoryImpl.exoPlayer

    /** Current engine type (used by the UI to choose rendering path). */
    val activeEngineType: EngineType get() = playerRepository.activeEngineType

    /** Whether an engine switch is in progress. */
    val isSwitchingEngine: Boolean get() = playerRepository.isSwitchingEngine

    init {
        observePlaybackState()
        observeEngineType()
        observeSubtitleStyle()
        observeNetworkTraffic()
        observeSeekSensitivity()
        startPositionUpdates()
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            playerRepository.playbackStateInfo.collect { info ->
                val newIsLoading = info.state == PlayerState.BUFFERING
                _uiState.update { state ->
                    state.copy(
                        isPlaying = info.isPlaying,
                        isLoading = newIsLoading,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = info.repeatMode,
                        errorMessage = info.errorMessage,
                    )
                }
                // Clear seek suppression once the engine is no longer buffering.
                // Covers READY (seek done), IDLE (stopped), ENDED, and ERROR (failed)
                // so isSeeking can never get permanently stuck.
                if (isSeeking && info.state != PlayerState.BUFFERING) {
                    isSeeking = false
                }
            }
        }
    }

    private fun observeEngineType() {
        viewModelScope.launch {
            playerRepository.activeEngineTypeFlow.collect { type ->
                _uiState.update { state ->
                    state.copy(activeEngineType = type)
                }
            }
        }
    }

    private fun observeSubtitleStyle() {
        viewModelScope.launch {
            userPreferencesRepository.subtitleStyle.collect { style ->
                _uiState.update { it.copy(subtitleStyle = style) }
            }
        }
    }

    private fun observeNetworkTraffic() {
        viewModelScope.launch {
            playerRepository.networkTraffic.collect { traffic ->
                _uiState.update { it.copy(networkTraffic = traffic) }
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                val engine = playerRepository.activeEngine
                val duration = engine.getDuration()
                val position = engine.getCurrentPosition()
                val bufferedPos = engine.getBufferedPosition()
                val bufferedPct = if (duration > 0) {
                    ((bufferedPos * 100) / duration).toInt().coerceIn(0, 100)
                } else 0
                val subTracks = playerRepository.getSubtitleTracks()
                val selectedSub = playerRepository.getSelectedSubtitleTrack()
                val audioTracks = playerRepository.getAudioTracks()
                val selectedAudio = playerRepository.getSelectedAudioTrack()
                val currentUri = playerRepository.currentPlaybackUri

                // Hold the seek target position until ExoPlayer fully settles.
                // isSeeking stays true for the entire BUFFERING window, so the
                // thumb never snaps back regardless of how slow the server is.
                val effectivePosition = if (isSeeking) seekTargetPosition else position

                _uiState.update { state ->
                    val tracksChanged = state.subtitleTracks != subTracks
                    val selectionChanged = state.selectedSubtitleTrack != selectedSub
                    val audioTracksChanged = state.audioTracks != audioTracks
                    val audioSelectionChanged = state.selectedAudioTrack != selectedAudio
                    val uriChanged = state.currentPlaybackUri != currentUri
                    if (tracksChanged || selectionChanged || audioTracksChanged || audioSelectionChanged || uriChanged || state.currentPosition != effectivePosition || state.duration != duration || state.bufferedPercentage != bufferedPct) {
                        state.copy(
                            currentPosition = effectivePosition,
                            duration = duration,
                            bufferedPercentage = bufferedPct,
                            subtitleTracks = if (tracksChanged) subTracks else state.subtitleTracks,
                            selectedSubtitleTrack = selectedSub,
                            audioTracks = if (audioTracksChanged) audioTracks else state.audioTracks,
                            selectedAudioTrack = selectedAudio,
                            currentPlaybackUri = currentUri
                        )
                    } else {
                        state
                    }
                }
            }
        }
    }

    fun selectSubtitleTrack(index: Int) {
        playerRepository.selectSubtitleTrack(index)
        val subTracks = playerRepository.getSubtitleTracks()
        val selectedSub = playerRepository.getSelectedSubtitleTrack()
        _uiState.update { state ->
            state.copy(
                subtitleTracks = subTracks,
                selectedSubtitleTrack = selectedSub
            )
        }
    }

    fun selectAudioTrack(index: Int) {
        playerRepository.selectAudioTrack(index)
        val audioTracks = playerRepository.getAudioTracks()
        val selectedAudio = playerRepository.getSelectedAudioTrack()
        _uiState.update { state ->
            state.copy(
                audioTracks = audioTracks,
                selectedAudioTrack = selectedAudio
            )
        }
    }

    fun addExternalSubtitle(uri: Uri, displayName: String? = null) {
        val name = displayName ?: uri.lastPathSegment ?: uri.toString()
        val success = playerRepository.addExternalSubtitle(uri)
        if (success) {
            val subTracks = playerRepository.getSubtitleTracks()
            val selectedSub = playerRepository.getSelectedSubtitleTrack()
            _uiState.update { state ->
                state.copy(
                    subtitleTracks = subTracks,
                    selectedSubtitleTrack = selectedSub,
                    externalSubtitles = state.externalSubtitles + (name to uri)
                )
            }
        }
    }

    fun onSubtitleDelayChange(delayMs: Long) {
        playerRepository.setSubtitleDelay(delayMs)
        _uiState.update { it.copy(subtitleDelayMs = delayMs) }
    }

    fun onSubtitleStyleChange(style: SubtitleStyle) {
        _uiState.update { it.copy(subtitleStyle = style) }
        viewModelScope.launch {
            userPreferencesRepository.setSubtitleStyle(style)
        }
    }

    fun onAspectRatioChange(mode: com.rhnxdev.hzplayer.domain.model.AspectRatioMode) {
        _uiState.update { it.copy(aspectRatioMode = mode) }
    }

    fun onToggleLock() {
        _uiState.update { it.copy(playerLocked = !it.playerLocked) }
    }

    fun onToggleOrientation(activity: android.app.Activity) {
        val rotation = activity.windowManager.defaultDisplay?.rotation
            ?: android.view.Surface.ROTATION_0
        activity.requestedOrientation = when (rotation) {
            android.view.Surface.ROTATION_0, android.view.Surface.ROTATION_180 ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
            else ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
        }
    }

    /**
     * Play a media file from its URI string.
     * Used by the file browser and external intents.
     */
    /** Mark current playback as video (hides the mini player bar). */
    fun onVideoStarted() {
        _uiState.update { it.copy(isVideo = true) }
    }

    fun playVideo(video: com.rhnxdev.hzplayer.domain.model.VideoItem) {
        _uiState.update { state ->
            state.copy(
                currentTitle = video.title,
                currentArtist = null,
                isVideo = true,
                isPlaying = true,
                isLoading = true,
                duration = video.durationMs,
                currentPlaybackUri = video.uri,
            )
        }
        playerRepository.playVideo(video)
    }

    fun playUri(
        uri: String,
        title: String,
        isVideo: Boolean = false,
        playImmediately: Boolean = true,
    ) {
        _uiState.update { state ->
            state.copy(
                currentTitle = title,
                currentArtist = null,
                isVideo = isVideo,
                isPlaying = playImmediately,
                isLoading = true,
                currentPlaybackUri = uri,
            )
        }
        playerRepository.playUri(uri, title, isVideo = isVideo)
    }

    fun onPlayPause() {
        playerRepository.togglePlayPause()
    }

    fun pause() {
        playerRepository.activeEngine.pause()
    }

    fun resume() {
        playerRepository.activeEngine.resume()
    }

    fun playAudio(audio: AudioItem) {
        _uiState.update {
            it.copy(
                currentTitle = audio.title,
                currentArtist = audio.artist,
                isVideo = false,
                isPlaying = true,
                isLoading = true,
                duration = audio.durationMs,
                currentPlaybackUri = audio.uri,
            )
        }
        playerRepository.playAudio(audio)
    }

    // Bug 3 fix: do NOT set isLoading=true manually — let the engine's
    // STATE_BUFFERING event drive it via observePlaybackState().
    // Bug 6 fix: debounce rapid seeks; ignore if one is already in flight.
    // Bug 1 fix: record seek target + timestamp for poller suppression.

    fun onSeekTo(positionMs: Long) {
        val now = System.currentTimeMillis()
        if (now - lastSeekTimestamp < SEEK_DEBOUNCE_MS) return
        markSeekStart(positionMs)
        playerRepository.seekTo(positionMs)
    }

    fun onSkipForward() {
        markSeekStart(_uiState.value.currentPosition + 10_000)
        playerRepository.skipForward(10000)
    }

    fun onSkipBackward() {
        markSeekStart((_uiState.value.currentPosition - 10_000).coerceAtLeast(0))
        playerRepository.skipBackward(10000)
    }

    fun onSetSpeed(speed: Float) {
        playerRepository.setSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun onToggleShuffle() {
        playerRepository.toggleShuffle()
    }

    fun onCycleRepeatMode() {
        playerRepository.cycleRepeatMode()
        val current = _uiState.value.repeatMode
        val next = when (current) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        _uiState.update { it.copy(repeatMode = next) }
    }

    fun stop() {
        android.util.Log.d("PlayerViewModel", "stop() called")
        playerRepository.stop()
        _uiState.update { it.copy(currentTitle = null, currentArtist = null, isPlaying = false, currentPlaybackUri = null) }
    }

    fun onToggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun onShowControls() {
        _uiState.update { it.copy(showControls = true) }
    }

    fun onHideControls() {
        _uiState.update { it.copy(showControls = false) }
    }

    fun onSeekBy(deltaMs: Long) {
        val target = (_uiState.value.currentPosition + deltaMs).coerceAtLeast(0)
        markSeekStart(target)
        if (deltaMs >= 0) playerRepository.skipForward(deltaMs)
        else playerRepository.skipBackward(-deltaMs)
    }

    fun playNetworkUri(uri: String, title: String, isVideo: Boolean) {
        val needsVlc = uri.startsWith("ftp://") ||
            uri.startsWith("sftp://") ||
            uri.startsWith("http://") ||
            uri.startsWith("https://") ||
            uri.startsWith("rtsp://")
        if (needsVlc && activeEngineType != EngineType.VLC) {
            viewModelScope.launch {
                playerRepository.switchEngine(EngineType.VLC)
                playerRepository.playUri(uri, title, isVideo = isVideo)
                _uiState.update { state ->
                    state.copy(
                        currentTitle = title,
                        currentArtist = null,
                        isVideo = isVideo,
                        isPlaying = true,
                        currentPlaybackUri = uri,
                    )
                }
            }
        } else {
            playUri(uri, title, isVideo)
        }
    }

    /** Switch the playback engine. */
    fun switchEngine(type: EngineType) {
        viewModelScope.launch {
            playerRepository.switchEngine(type)
        }
    }

    private fun observeSeekSensitivity() {
        viewModelScope.launch {
            userPreferencesRepository.seekSensitivity.collect { sensitivity ->
                _uiState.update { it.copy(seekSensitivity = sensitivity) }
            }
        }
    }

    /** Record seek state for the poller suppress window (Bug 1) and debounce (Bug 6). */
    private fun markSeekStart(targetMs: Long) {
        isSeeking = true
        lastSeekTimestamp = System.currentTimeMillis()
        seekTargetPosition = targetMs.coerceAtLeast(0)
    }

    override fun onCleared() {
        super.onCleared()
        android.util.Log.d("PlayerViewModel", "onCleared() called")
        positionUpdateJob?.cancel()
        playerRepository.stop()
    }
}

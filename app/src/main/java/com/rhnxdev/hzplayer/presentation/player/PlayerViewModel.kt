package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val resumeRepository: ResumeRepository,
    private val mediaDao: MediaDao,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var positionUpdateJob: Job? = null

    // Outlives viewModelScope so a final save during onCleared() still completes.
    private val saveScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var saveTick = 0

    private var isSeeking = false
    private var lastSeekTimestamp = 0L
    private var seekTargetPosition = 0L

    // Cached track lists — updated only after explicit track selection or READY state
    private var cachedSubtitleTracks: List<String> = emptyList()
    private var cachedSelectedSubtitle: Int = -1
    private var cachedAudioTracks: List<String> = emptyList()
    private var cachedSelectedAudio: Int = -1
    private var trackRefreshNeeded = false
    private var debugPollJob: Job? = null

    companion object {
        private const val TAG = "PlayerViewModel"
        private const val SEEK_DEBOUNCE_MS = 150L
    }

    fun getActiveEngine(): IPlayerEngine = playerRepository.activeEngine

    init {
        observePlaybackState()
        observeSubtitleStyle()
        observeNetworkTraffic()
        observeSeekSensitivity()
        observeHdrSettings()
        observeActiveEngine()
        observeDebugMode()
        startPositionUpdates()
        refreshTrackCache()
    }

    /**
     * Subscribe to the user's "Enable HDR playback" preference.
     *
     * The pref is still persisted and exposed in settings — but the actual video
     * pipeline now has no HDR/SDR mode swap, so this observer keeps the value
     * flowing into [PlayerUiState.hdrEnabled] (for any future consumer) without
     * touching the surface, window color mode, or effect chain.
     */
    private fun observeHdrSettings() {
        viewModelScope.launch {
            userPreferencesRepository.enableHdrPlayback
                .distinctUntilChanged()
                .collect { enabled ->
                    _uiState.update { it.copy(hdrEnabled = enabled) }
                }
        }
    }

    private fun refreshTrackCache() {
        trackRefreshNeeded = false
        cachedSubtitleTracks = playerRepository.getSubtitleTracks()
        cachedSelectedSubtitle = playerRepository.getSelectedSubtitleTrack()
        cachedAudioTracks = playerRepository.getAudioTracks()
        cachedSelectedAudio = playerRepository.getSelectedAudioTrack()
        _uiState.update {
            it.copy(
                subtitleTracks = cachedSubtitleTracks,
                selectedSubtitleTrack = cachedSelectedSubtitle,
                audioTracks = cachedAudioTracks,
                selectedAudioTrack = cachedSelectedAudio,
            )
        }
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            playerRepository.playbackStateInfo.collect { info ->
                val newIsLoading = info.state == PlayerState.BUFFERING
                
                val artworkUri = info.currentUri?.let { uri ->
                    mediaDao.getByUri(uri)?.albumArtUri
                }

                val isIdle = info.state == PlayerState.IDLE

                _uiState.update { state ->
                    state.copy(
                        isPlaying = info.isPlaying,
                        isLoading = newIsLoading,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = info.repeatMode,
                        errorMessage = info.errorMessage,
                        errorKind = info.errorKind,
                        currentTitle = if (isIdle) null else (info.currentTitle ?: state.currentTitle),
                        currentArtist = if (isIdle) null else (info.currentArtist ?: state.currentArtist),
                        currentPlaybackUri = if (isIdle) null else (info.currentUri ?: state.currentPlaybackUri),
                        currentArtworkUri = if (isIdle) null else (artworkUri ?: if (info.currentUri != state.currentPlaybackUri) null else state.currentArtworkUri),
                        // Track DRM status — exposed in `drmSessionActive` for any
                        // future pipeline that needs it; not currently driving the
                        // surface selection while HDR/SDR colour-correction is in
                        // progress.
                        drmSessionActive = info.drmSessionActive,
                    )
                }
                // Refresh track cache once ExoPlayer finishes preparing (state == READY).
                // querying tracks before prepare() returns empty because currentTracks is
                // populated asynchronously by the media pipeline.
                if (info.state == PlayerState.READY && trackRefreshNeeded) {
                    refreshTrackCache()
                }
                if (isSeeking && info.state != PlayerState.BUFFERING) {
                    isSeeking = false
                }
            }
        }
    }

    private fun observeActiveEngine() {
        viewModelScope.launch {
            userPreferencesRepository.activeEngine
                .distinctUntilChanged()
                .collect { type -> _uiState.update { it.copy(activeEngineType = type) } }
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
                val currentUri = playerRepository.currentPlaybackUri

                val effectivePosition = if (isSeeking) seekTargetPosition else position

                _uiState.update { state ->
                    val uriChanged = state.currentPlaybackUri != currentUri
                    if (uriChanged || state.currentPosition != effectivePosition || state.duration != duration || state.bufferedPercentage != bufferedPct) {
                        state.copy(
                            currentPosition = effectivePosition,
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
                        saveScope.launch { resumeRepository.saveProgress(currentUri, position, duration) }
                    }
                }
            }
        }
    }

    /** Read engine position on the current (main) thread, persist off-thread. */
    private fun saveProgressNow() {
        val uri = playerRepository.currentPlaybackUri ?: return
        val engine = playerRepository.activeEngine
        val pos = engine.getCurrentPosition()
        val dur = engine.getDuration()
        saveScope.launch { resumeRepository.saveProgress(uri, pos, dur) }
    }

    /** Seek to the saved position for [uri], if any. Runs on main (ExoPlayer requires it). */
    private fun restoreProgress(uri: String) {
        viewModelScope.launch {
            val pos = resumeRepository.getResumePosition(uri)
            if (pos > 0) playerRepository.seekTo(pos)
        }
    }

    fun selectSubtitleTrack(index: Int) {
        playerRepository.selectSubtitleTrack(index)
        cachedSubtitleTracks = playerRepository.getSubtitleTracks()
        cachedSelectedSubtitle = playerRepository.getSelectedSubtitleTrack()
        _uiState.update { state ->
            state.copy(
                subtitleTracks = cachedSubtitleTracks,
                selectedSubtitleTrack = cachedSelectedSubtitle,
            )
        }
    }

    fun selectAudioTrack(index: Int) {
        playerRepository.selectAudioTrack(index)
        cachedAudioTracks = playerRepository.getAudioTracks()
        cachedSelectedAudio = playerRepository.getSelectedAudioTrack()
        _uiState.update { state ->
            state.copy(
                audioTracks = cachedAudioTracks,
                selectedAudioTrack = cachedSelectedAudio,
            )
        }
    }

    fun addExternalSubtitle(uri: Uri, displayName: String? = null) {
        val name = displayName ?: uri.lastPathSegment ?: uri.toString()
        val success = playerRepository.addExternalSubtitle(uri)
        if (success) {
            refreshTrackCache()
            _uiState.update { state ->
                state.copy(
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

    fun onVideoStarted() {
        _uiState.update { it.copy(isVideo = true) }
    }

    fun playVideo(video: com.rhnxdev.hzplayer.domain.model.VideoItem) {
        playerRepository.playVideo(video)
        restoreProgress(video.uri)
        trackRefreshNeeded = true
        _uiState.update { state ->
            state.copy(
                currentTitle = video.title,
                currentArtist = null,
                currentArtworkUri = video.thumbnailUri,
                isVideo = true,
                isPlaying = true,
                isLoading = true,
                duration = video.durationMs,
                currentPlaybackUri = video.uri,
                videoPlaylist = emptyList(),
            )
        }
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
                currentArtworkUri = null,
                isVideo = isVideo,
                isPlaying = playImmediately,
                isLoading = true,
                currentPlaybackUri = uri,
                videoPlaylist = emptyList(),
            )
        }
        playerRepository.playUri(uri, title, isVideo = isVideo)
        restoreProgress(uri)
        trackRefreshNeeded = true
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
                currentArtworkUri = audio.albumArtUri,
                isVideo = false,
                isPlaying = true,
                isLoading = true,
                duration = audio.durationMs,
                currentPlaybackUri = audio.uri,
                videoPlaylist = emptyList(),
            )
        }
        playerRepository.playAudio(audio)
        trackRefreshNeeded = true
    }

    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val item = items[startIndex.coerceIn(0, items.lastIndex)]
        _uiState.update { state ->
            state.copy(
                currentTitle = item.title,
                currentArtist = item.artist,
                currentArtworkUri = item.albumArtUri,
                isVideo = false,
                isPlaying = true,
                isLoading = true,
                duration = item.durationMs,
                currentPlaybackUri = item.uri,
                videoPlaylist = emptyList(),
            )
        }
        playerRepository.playAudioPlaylist(items, startIndex)
        trackRefreshNeeded = true
    }

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

    fun onSkipNext() {
        playerRepository.skipToNext()
    }

    fun onSkipPrevious() {
        playerRepository.skipToPrevious()
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
        android.util.Log.d(TAG, "stop() called")
        saveProgressNow()
        playerRepository.stop()
        _uiState.update { it.copy(
            currentTitle = null, 
            currentArtist = null, 
            isPlaying = false, 
            currentPlaybackUri = null, 
            videoPlaylist = emptyList(),
            errorMessage = null
        ) }
    }

    fun clearError() {
        playerRepository.clearError()
        _uiState.update { it.copy(errorMessage = null, errorKind = null) }
    }

    /** Re-attempt the last playback after a recoverable error. */
    fun retry() {
        playerRepository.retry()
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
        android.util.Log.d(TAG, "playNetworkUri: scheme=${uri.substringBefore("://")} uri=$uri")
        playUri(uri, title, isVideo)
    }

    fun playVideoPlaylist(items: List<VideoItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val item = items[startIndex.coerceIn(0, items.lastIndex)]
        _uiState.update { state ->
            state.copy(
                currentTitle = item.title,
                currentArtist = null,
                isVideo = true,
                isPlaying = true,
                isLoading = true,
                duration = item.durationMs,
                currentPlaybackUri = item.uri,
                videoPlaylist = items,
                currentPlaylistIndex = startIndex,
                showPlaylistDrawer = false,
            )
        }
        val playlistItems: List<Pair<String, String>> = items.map { it.uri to it.title }
        playerRepository.playPlaylist(playlistItems, startIndex, 0L)
        trackRefreshNeeded = true
    }

    fun onPlaylistNext(): Boolean {
        val playlist = _uiState.value.videoPlaylist
        if (playlist.isEmpty()) return false
        val nextIndex = _uiState.value.currentPlaylistIndex + 1
        if (nextIndex >= playlist.size) return false
        val item = playlist[nextIndex]
        _uiState.update { it.copy(currentPlaylistIndex = nextIndex, currentTitle = item.title, currentPlaybackUri = item.uri, duration = item.durationMs) }
        playerRepository.seekTo(0)
        playerRepository.activeEngine.play(item.uri, item.title, isVideo = true)
        return true
    }

    fun onPlaylistPrevious(): Boolean {
        val playlist = _uiState.value.videoPlaylist
        if (playlist.isEmpty()) return false
        val prevIndex = _uiState.value.currentPlaylistIndex - 1
        if (prevIndex < 0) return false
        val item = playlist[prevIndex]
        _uiState.update { it.copy(currentPlaylistIndex = prevIndex, currentTitle = item.title, currentPlaybackUri = item.uri, duration = item.durationMs) }
        playerRepository.seekTo(0)
        playerRepository.activeEngine.play(item.uri, item.title, isVideo = true)
        return true
    }

    fun onPlaylistSelect(index: Int) {
        val playlist = _uiState.value.videoPlaylist
        if (index !in playlist.indices) return
        val item = playlist[index]
        _uiState.update { it.copy(currentPlaylistIndex = index, currentTitle = item.title, currentPlaybackUri = item.uri, duration = item.durationMs, showPlaylistDrawer = false) }
        playerRepository.seekTo(0)
        playerRepository.activeEngine.play(item.uri, item.title, isVideo = true)
    }

    fun onTogglePlaylistDrawer() {
        _uiState.update {
            val opening = !it.showPlaylistDrawer
            it.copy(
                showPlaylistDrawer = opening,
                // Opening the drawer hides the HUD; hiding the HUD also hides the
                // system bars (nav bar) via the showControls → systemBars sync in the screen.
                showControls = if (opening) false else it.showControls,
            )
        }
    }

    fun clearPlaylist() {
        _uiState.update { it.copy(videoPlaylist = emptyList(), showPlaylistDrawer = false) }
    }

    private fun observeDebugMode() {
        viewModelScope.launch {
            userPreferencesRepository.debugMode.collect { enabled ->
                _uiState.update { it.copy(debugMode = enabled) }
                if (enabled && _uiState.value.debugOverlayVisible) startDebugPolling()
                else stopDebugPolling()
            }
        }
    }

    fun onToggleDebugOverlay() {
        val show = !_uiState.value.debugOverlayVisible
        _uiState.update { it.copy(debugOverlayVisible = show) }
        if (show && _uiState.value.debugMode) startDebugPolling()
        else stopDebugPolling()
    }

    private fun startDebugPolling() {
        debugPollJob?.cancel()
        debugPollJob = viewModelScope.launch {
            while (isActive) {
                try {
                    val stats = playerRepository.getDebugStats() ?: DebugStats()
                    val nt = _uiState.value.networkTraffic
                    val speedDown = nt.speedDown
                    val duration = _uiState.value.duration
                    // compute avg bitrate from total bytes / duration when fmt.bitrate is 0
                    val totalBytes = nt.bytesDown
                    val videoBitrate = if (stats.videoBitrate.isEmpty() && duration > 0 && totalBytes > 0) {
                        formatBitsPerSecond(((totalBytes * 8) / (duration / 1000)).toLong())
                    } else if (stats.videoBitrate.isNotEmpty()) {
                        bitsToHuman(stats.videoBitrate)
                    } else ""
                    val audioBitrate = if (stats.audioBitrate.isNotEmpty()) bitsToHuman(stats.audioBitrate) else ""
                    _uiState.update { it.copy(
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
        _uiState.update { it.copy(debugStats = DebugStats()) }
    }

    private fun bitsToHuman(bitrateStr: String): String {
        val bps = bitrateStr.toLongOrNull() ?: return bitrateStr
        return formatBitsPerSecond(bps)
    }

    private fun formatBitsPerSecond(bps: Long): String = when {
        bps < 1_000 -> "$bps bps"
        bps < 1_000_000 -> "${bps / 1000} kbps"
        else -> "${"%.2f".format(bps.toDouble() / 1_000_000)} Mbps"
    }

    private fun formatDebugSpeed(bytesPerSec: Long): String = when {
        bytesPerSec < 1024 -> "$bytesPerSec B/s"
        bytesPerSec < 1024 * 1024 -> "%.1f KB/s".format(bytesPerSec / 1024.0)
        else -> "%.1f MB/s".format(bytesPerSec / (1024.0 * 1024.0))
    }

    private fun formatDebugBytes(bytes: Long): String = when {
        bytes < 1024 -> "$bytes B"
        bytes < 1024 * 1024 -> "%.1f KB".format(bytes / 1024.0)
        bytes < 1024L * 1024 * 1024 -> "%.1f MB".format(bytes / (1024.0 * 1024.0))
        else -> "%.2f GB".format(bytes / (1024.0 * 1024.0 * 1024.0))
    }


    private fun observeSeekSensitivity() {
        viewModelScope.launch {
            userPreferencesRepository.seekSensitivity.collect { sensitivity ->
                _uiState.update { it.copy(seekSensitivity = sensitivity) }
            }
        }
    }

    private fun markSeekStart(targetMs: Long) {
        isSeeking = true
        lastSeekTimestamp = System.currentTimeMillis()
        seekTargetPosition = targetMs.coerceAtLeast(0)
    }

    override fun onCleared() {
        super.onCleared()
        android.util.Log.d(TAG, "onCleared() called")
        positionUpdateJob?.cancel()
        saveProgressNow()
        playerRepository.stop()
    }
}

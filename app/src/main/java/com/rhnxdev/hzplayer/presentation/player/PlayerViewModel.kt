package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.OrientationMode
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.isLibassSubtitleMimeType
import com.rhnxdev.hzplayer.core.util.bitsToHuman
import com.rhnxdev.hzplayer.core.util.formatBitsPerSecond
import com.rhnxdev.hzplayer.core.util.formatDebugBytes
import com.rhnxdev.hzplayer.core.util.formatDebugSpeed
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import com.rhnxdev.hzplayer.data.datasource.local.room.dao.MediaDao
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val userPreferencesRepository: UserPreferencesRepository,
    private val resumeProgress: ResumeRepository,
    private val mediaDao: MediaDao,
    val assHandler: com.rhnxdev.hzplayer.data.datasource.subtitle.assrender.AssHandler,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private val positionController = PlayerPositionController(
        scope = viewModelScope,
        playerRepository = playerRepository,
        resumeProgress = resumeProgress,
        uiState = _uiState,
    )

    /**
     * High-frequency playback position (ms), updated every 250 ms. Kept separate
     * from [uiState] so the tick only recomposes the seek bar, not the whole UI.
     */
    val position: StateFlow<Long> = positionController.position

    /**
     * Real-time network throughput (polled during streaming). Kept out of
     * [uiState] so the per-tick update only recomposes [NetworkSpeedChip], not
     * the whole player screen.
     */
    val networkTraffic: StateFlow<NetworkTraffic> =
        playerRepository.networkTraffic.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), NetworkTraffic.DEFAULT)

    private val debugController = PlayerDebugController(
        scope = viewModelScope,
        playerRepository = playerRepository,
        userPreferencesRepository = userPreferencesRepository,
        uiState = _uiState,
        networkTrafficFlow = networkTraffic,
    )

    /**
     * "Stats for nerds" snapshot (refreshed ~1 Hz while the debug overlay is on).
     * Kept out of [uiState] so the poll only recomposes [DebugOverlay].
     */
    val debugStats: StateFlow<DebugStats> =
        debugController.debugStats

    val orientationMode: StateFlow<OrientationMode> = userPreferencesRepository.orientationMode
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), OrientationMode.AUTO)

    /** Floating video player (in-app mini + system PiP) master toggle. */
    val backgroundPlay: StateFlow<Boolean> = userPreferencesRepository.backgroundPlay
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), false)

    /**
     * Set when minimizing to the floating window so the full-screen surface's
     * ON_STOP (fired by the NavBackStackEntry when the route is popped) does NOT
     * pause playback. The engine is a singleton and must keep running so the
     * in-app mini player can take over the surface.
     */
    var isMinimizing = false

    private var subtitlePreferenceAppliedForUri: String? = null

    private fun applySubtitlePreference() {
        val currentUri = _uiState.value.currentPlaybackUri ?: return
        if (subtitlePreferenceAppliedForUri == currentUri) return

        val tracks = _uiState.value.subtitleTracks
        if (tracks.isEmpty()) return

        subtitlePreferenceAppliedForUri = currentUri
        // Query the engine live: a neighbor subtitle auto-loaded off-thread may
        // already be the active (libass) track before the UI state reflects it.
        // Reading stale _uiState here would wrongly re-select index 0 (an empty
        // embedded track), wiping the auto-detected subtitle.
        val currentSelected = runCatching { getActiveEngine().getSelectedSubtitleTrack() }
            .getOrDefault(_uiState.value.selectedSubtitleTrack)

        android.util.Log.i("HzSubPref", "Applying subtitle preference: currentSelected=$currentSelected tracks=$tracks")

        // Auto-enable when nothing is chosen. Prefer an auto-detected external
        // (libass) track over an embedded one: neighbor subs sort last, so the
        // external index is preferred so its content actually shows instead of an
        // empty embedded track at index 0.
        if (currentSelected == -1) {
            val mimes = runCatching { getActiveEngine().getSubtitleTrackMimeTypes() }
                .getOrDefault(emptyList())
            val externalIdx = mimes.indexOfLast { isLibassSubtitleMimeType(it?.lowercase()) }
            selectSubtitleTrack(if (externalIdx >= 0) externalIdx else 0)
        } else if (currentSelected != _uiState.value.selectedSubtitleTrack) {
            // Sync UI to the already-active (auto-loaded) track.
            _uiState.update { it.copy(selectedSubtitleTrack = currentSelected) }
        }
    }

    // Cached track lists — updated only after explicit track selection or READY state
    private val trackCache = PlayerTrackCache(
        playerRepository = playerRepository,
        uiState = _uiState,
    )

    private val playlistController = PlayerPlaylistController(
        playerRepository = playerRepository,
        uiState = _uiState,
        trackCache = trackCache,
    )

    /** Current resume preference, kept in sync from DataStore. */
    private var resumeMode: com.rhnxdev.hzplayer.domain.model.ResumeMode =
        com.rhnxdev.hzplayer.domain.model.ResumeMode.ALWAYS

    companion object {
        private const val TAG = "PlayerViewModel"
        /** Positions below this are treated as "not really started" — no resume prompt. */
        private const val RESUME_THRESHOLD_MS = 5_000L
    }

    fun getActiveEngine(): IPlayerEngine = playerRepository.activeEngine

    init {
        observePlaybackState()
        observeSeekSensitivity()
        observeActiveEngine()
        observeResumeMode()
        debugController.observe()
        positionController.start()
        trackCache.refresh()
        // External subtitles load off-thread; when their libass registration
        // changes, refresh the merged track list so the dialog shows them.
        playerRepository.activeEngine.subtitleTrackChangeListener = {
            trackCache.refresh()
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
                    val oldUri = state.currentPlaybackUri
                    if (info.currentUri != null && info.currentUri != oldUri) {
                        subtitlePreferenceAppliedForUri = null
                        trackCache.markNeedsRefresh()
                    }
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
                if (info.state == PlayerState.READY) {
                    trackCache.refreshIfNeeded()
                    applySubtitlePreference()
                }
                positionController.onPlaybackState(info.state)
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

    private fun observeResumeMode() {
        viewModelScope.launch {
            userPreferencesRepository.resumeMode.collect { resumeMode = it }
        }
    }

    fun selectSubtitleTrack(index: Int) {
        val mimes = runCatching { getActiveEngine().getSubtitleTrackMimeTypes() }
            .getOrDefault(emptyList())
        val mime = mimes.getOrNull(index)?.lowercase()
        val isLibass = isLibassSubtitleMimeType(mime)
        android.util.Log.i(
            "HzAss",
            "selectSubtitleTrack idx=$index mime=$mime isLibass=$isLibass " +
                "allMimes=$mimes",
        )

        if (isLibass) {
            // Engine routes ASS/SSA/SRT/VTT through libass (SRT/VTT converted first).
            getActiveEngine().selectSubtitleTrack(index)
            _uiState.update {
                it.copy(selectedSubtitleTrack = index)
            }
        } else {
            trackCache.selectSubtitleTrack(index)
        }
    }

    fun selectAudioTrack(index: Int) = trackCache.selectAudioTrack(index)

    fun addExternalSubtitle(uri: Uri, displayName: String? = null) {
        val name = displayName ?: uri.lastPathSegment ?: uri.toString()
        // Engine routes ASS/SSA + convertible SRT/VTT through libass (converting
        // SRT/VTT to ASS first); anything else falls back to ExoPlayer's renderer.
        val success = playerRepository.addExternalSubtitle(uri)
        if (success) {
            trackCache.refresh()
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
     * Apply the user's orientation preference. When [OrientationMode.AUTO] the
     * screen follows the sensor; otherwise it is locked to portrait/landscape.
     */
    fun applyOrientationMode(activity: android.app.Activity, mode: com.rhnxdev.hzplayer.domain.model.OrientationMode) {
        activity.requestedOrientation = when (mode) {
            com.rhnxdev.hzplayer.domain.model.OrientationMode.AUTO ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
            com.rhnxdev.hzplayer.domain.model.OrientationMode.PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            com.rhnxdev.hzplayer.domain.model.OrientationMode.LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        }
    }

    fun onVideoStarted() {
        _uiState.update { it.copy(isVideo = true) }
    }

    fun playVideo(video: com.rhnxdev.hzplayer.domain.model.VideoItem) {
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
        // Decide how to handle a saved resume position, then start accordingly.
        viewModelScope.launch {
            val resumePos = resumeProgress.getResumePosition(video.uri)
            startWithResumeDecision(
                uri = video.uri,
                title = video.title,
                isVideo = true,
                mimeType = video.mimeType,
                artist = null,
                savedPositionMs = resumePos,
                play = { pos -> playerRepository.playVideo(video, resumePositionMs = pos) },
            )
        }
        trackCache.markNeedsRefresh()
    }

    fun playUri(
        uri: String,
        title: String,
        isVideo: Boolean = false,
        playImmediately: Boolean = true,
        mimeType: String? = null,

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
        viewModelScope.launch {
            val resumePos = resumeProgress.getResumePosition(uri)
            startWithResumeDecision(
                uri = uri,
                title = title,
                isVideo = isVideo,
                mimeType = mimeType,
                artist = null,
                savedPositionMs = resumePos,
                play = { pos -> playerRepository.playUri(uri, title, isVideo = isVideo, mimeType = mimeType, resumePositionMs = pos) },
            )
        }
        trackCache.markNeedsRefresh()
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
        viewModelScope.launch {
            val resumePos = resumeProgress.getResumePosition(audio.uri)
            startWithResumeDecision(
                uri = audio.uri,
                title = audio.title,
                isVideo = false,
                mimeType = null,
                artist = audio.artist,
                savedPositionMs = resumePos,
                id = audio.id,
                play = { pos -> playerRepository.playAudio(audio, resumePositionMs = pos) },
            )
        }
        trackCache.markNeedsRefresh()
    }

    /**
     * Apply the user's resume preference to a saved position and start playback.
     * - NONE: always start from 0.
     * - ALWAYS: resume from [savedPositionMs] if it is past a small threshold.
     * - ASK: if there is a meaningful saved position, surface a confirm dialog
     *   (pendingResume) instead of starting immediately; the user confirms via
     *   [confirmResume] or dismisses via [dismissResume].
     */
    private fun startWithResumeDecision(
        uri: String,
        title: String,
        isVideo: Boolean,
        mimeType: String?,
        artist: String?,
        savedPositionMs: Long,
        id: Long = 0,
        play: (Long) -> Unit,
    ) {
        val hasProgress = savedPositionMs > RESUME_THRESHOLD_MS
        when (resumeMode) {
            com.rhnxdev.hzplayer.domain.model.ResumeMode.NONE -> play(0)
            com.rhnxdev.hzplayer.domain.model.ResumeMode.ALWAYS -> play(savedPositionMs)
            com.rhnxdev.hzplayer.domain.model.ResumeMode.ASK -> {
                if (hasProgress) {
                    _uiState.update {
                        it.copy(
                            pendingResume = PendingResume(
                                uri = uri,
                                resumePositionMs = savedPositionMs,
                                title = title,
                                isVideo = isVideo,
                                mimeType = mimeType,
                                artist = artist,
                                id = id,
                            ),
                        )
                    }
                } else {
                    play(0)
                }
            }
        }
    }

    /** User confirmed resume from the pending position. */
    fun confirmResume() {
        val pending = _uiState.value.pendingResume ?: return
        _uiState.update { it.copy(pendingResume = null) }
        startPending(pending, resume = true)
    }

    /** User dismissed the resume prompt — start from the beginning. */
    fun dismissResume() {
        val pending = _uiState.value.pendingResume ?: return
        _uiState.update { it.copy(pendingResume = null) }
        startPending(pending, resume = false)
    }

    private fun startPending(pending: PendingResume, resume: Boolean) {
        val pos = if (resume) pending.resumePositionMs else 0
        if (pending.isVideo) {
            playerRepository.playUri(
                pending.uri,
                pending.title,
                isVideo = true,
                mimeType = pending.mimeType,
                resumePositionMs = pos,
            )
        } else {
            // Audio without a full AudioItem: reconstruct a minimal one so the
            // engine gets artist metadata; resume position is applied via the play call.
            val audio = AudioItem(
                id = pending.id,
                uri = pending.uri,
                title = pending.title,
                artist = pending.artist,
                durationMs = 0,
            )
            playerRepository.playAudio(audio, resumePositionMs = pos)
        }
        trackCache.markNeedsRefresh()
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
        trackCache.markNeedsRefresh()
    }

    fun onSeekTo(positionMs: Long) = positionController.onSeekTo(positionMs)

    fun onSkipForward() = positionController.onSkipForward()

    fun onSkipBackward() = positionController.onSkipBackward()

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
        android.util.Log.i(TAG, "stop() called")
        positionController.saveProgressNow()
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

    fun onSeekBy(deltaMs: Long) = positionController.onSeekBy(deltaMs)

    fun playNetworkUri(uri: String, title: String, isVideo: Boolean, mimeType: String? = null) {
        android.util.Log.i(TAG, "playNetworkUri: scheme=${uri.substringBefore("://")} uri=$uri")
        playUri(uri, title, isVideo, mimeType = mimeType)
    }

    fun playVideoPlaylist(items: List<VideoItem>, startIndex: Int = 0) =
        playlistController.playVideoPlaylist(items, startIndex)

    fun onPlaylistNext(): Boolean = playlistController.onPlaylistNext()

    fun onPlaylistPrevious(): Boolean = playlistController.onPlaylistPrevious()

    fun onPlaylistSelect(index: Int) = playlistController.onPlaylistSelect(index)

    fun onTogglePlaylistDrawer() = playlistController.onTogglePlaylistDrawer()

    fun clearPlaylist() = playlistController.clearPlaylist()

    fun onToggleDebugOverlay() = debugController.onToggleDebugOverlay()

    private fun observeSeekSensitivity() {
        viewModelScope.launch {
            userPreferencesRepository.seekSensitivity.collect { sensitivity ->
                _uiState.update { it.copy(seekSensitivity = sensitivity) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        android.util.Log.i(TAG, "onCleared() called")
        positionController.onCleared()
        playerRepository.stop()
    }
}

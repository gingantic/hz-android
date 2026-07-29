package com.rhnxdev.hzplayer.presentation.player

import android.content.Context
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
import com.rhnxdev.hzplayer.core.thumbnail.MediaInfoProbe
import com.rhnxdev.hzplayer.core.util.bitsToHuman
import com.rhnxdev.hzplayer.core.util.formatBitsPerSecond
import com.rhnxdev.hzplayer.core.util.formatDebugBytes
import com.rhnxdev.hzplayer.core.util.formatDebugSpeed
import com.rhnxdev.hzplayer.domain.repository.ResumeRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    @param:ApplicationContext private val appContext: Context,
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

    /** Last saved player volume (0.0–1.0), or -1f if never saved. */
    val lastVolume: StateFlow<Float> = userPreferencesRepository.lastVolume
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1f)

    /** Last saved screen brightness (0.0–1.0), or -1f meaning use system brightness. */
    val lastBrightness: StateFlow<Float> = userPreferencesRepository.lastBrightness
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), -1f)

    /** Toggle state for saving/restoring brightness & volume. */
    val saveVolumeBrightnessState: StateFlow<Boolean> = userPreferencesRepository.saveVolumeBrightnessState
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), true)

    /**
     * Set when minimizing to the floating window so the full-screen surface's
     * ON_STOP (fired by the NavBackStackEntry when the route is popped) does NOT
     * pause playback. The engine is a singleton and must keep running so the
     * in-app mini player can take over the surface.
     */
    var isMinimizing = false

    /**
     * Set to true by the Activity in onDestroy() to signal that the ViewModel
     * should stop the singleton player. When false (default), onCleared() skips
     * stop() so config changes and process-death recreation don't kill playback.
     */
    var isShuttingDown = false

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
        /** Sentinel passed to [onSetSleepTimer]: stop when the current video ends. */
        const val SLEEP_TIMER_END_OF_VIDEO = -1L
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
                    // Keep the audio queue index in sync when the engine advances
                    // to the next/previous item in an audio playlist.
                    val newQueueIndex = if (!state.isVideo && state.audioQueue.isNotEmpty()
                        && info.currentUri != null && info.currentUri != oldUri
                    ) {
                        state.audioQueue.indexOfFirst { it.uri == info.currentUri }
                            .takeIf { it >= 0 } ?: state.audioQueueIndex
                    } else {
                        state.audioQueueIndex
                    }
                    // Track whether the video surface is actively presenting frames.
                    // Used by VideoPlayerScreen to manage the window HDR color mode.
                    val videoActive = state.isVideo &&
                        (info.state == PlayerState.READY || info.state == PlayerState.BUFFERING)
                    // Drop stale chapters as soon as the engine moves to new media;
                    // loadChaptersIfNeeded() re-probes once the new item is READY.
                    val uriChanged = info.currentUri != null && info.currentUri != oldUri
                    val chapters = if (uriChanged) {
                        emptyList()
                    } else {
                        state.chapters
                    }
                    // Keep the video playlist index in sync when the engine
                    // auto-advances (including while played as background audio),
                    // so reopening as video lands on the right item / drawer row.
                    val newPlaylistIndex = if (uriChanged && state.videoPlaylist.isNotEmpty()) {
                        state.videoPlaylist.indexOfFirst { it.uri == info.currentUri }
                            .takeIf { it >= 0 } ?: state.currentPlaylistIndex
                    } else {
                        state.currentPlaylistIndex
                    }
                    // "Play as audio" survives track changes within a playlist so the
                    // mini player keeps offering "Open as video" for every item; it
                    // only ends when playback goes idle or a non-playlist URI loads.
                    val stillPlaylistAudio = state.playingVideoAsAudio &&
                        state.videoPlaylist.isNotEmpty() &&
                        info.currentUri != null &&
                        state.videoPlaylist.any { it.uri == info.currentUri }
                    val playingVideoAsAudio = when {
                        isIdle -> false
                        uriChanged -> stillPlaylistAudio
                        else -> state.playingVideoAsAudio
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
                        audioQueueIndex = newQueueIndex,
                        currentPlaylistIndex = newPlaylistIndex,
                        // Track DRM status — exposed in `drmSessionActive` for any
                        // future pipeline that needs it; not currently driving the
                        // surface selection while HDR/SDR colour-correction is in
                        // progress.
                        drmSessionActive = info.drmSessionActive,
                        isVideoSurfaceActive = videoActive,
                        chapters = chapters,
                        // A new media item ends any A-B loop from the previous one.
                        abLoopStartMs = if (uriChanged || isIdle) null else state.abLoopStartMs,
                        abLoopEndMs = if (uriChanged || isIdle) null else state.abLoopEndMs,
                        // Survives track changes within a playlist (see above).
                        playingVideoAsAudio = playingVideoAsAudio,
                    )
                }
                // Refresh track cache once ExoPlayer finishes preparing (state == READY).
                // querying tracks before prepare() returns empty because currentTracks is
                // populated asynchronously by the media pipeline.
                if (info.state == PlayerState.READY) {
                    trackCache.refreshIfNeeded()
                    applySubtitlePreference()
                    loadChaptersIfNeeded()
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

    /**
     * Re-query the engine's track lists/selection into the UI state. Called right
     * before the subtitle dialog opens so the displayed indices match the engine's
     * live tracks — a stale cached list could map a tapped external track onto an
     * embedded index and wipe the libass overlay on selection.
     */
    fun refreshSubtitleTracks() {
        trackCache.refresh()
    }

    fun onSubtitleDelayChange(delayMs: Long) {
        playerRepository.setSubtitleDelay(delayMs)
        _uiState.update { it.copy(subtitleDelayMs = delayMs) }
    }

    fun onAudioDelayChange(delayMs: Long) {
        playerRepository.setAudioDelay(delayMs)
        _uiState.update { it.copy(audioDelayMs = delayMs) }
    }

    /** Live equalizer snapshot; an unavailable default when the engine has no EQ. */
    val equalizerState: StateFlow<com.rhnxdev.hzplayer.domain.model.EqualizerInfo> =
        playerRepository.getEqualizerState()
            ?: MutableStateFlow(com.rhnxdev.hzplayer.domain.model.EqualizerInfo())

    fun onEqualizerEnabledChange(enabled: Boolean) = playerRepository.setEqualizerEnabled(enabled)

    fun onEqualizerBandChange(band: Int, levelMb: Int) =
        playerRepository.setEqualizerBandLevel(band, levelMb)

    fun onEqualizerPresetSelect(preset: Int) = playerRepository.applyEqualizerPreset(preset)

    fun onEqualizerReset() = playerRepository.resetEqualizerBands()

    fun onBassBoostChange(strength: Int) = playerRepository.setBassBoostStrength(strength)

    fun onLoudnessGainChange(gainMb: Int) = playerRepository.setLoudnessGain(gainMb)

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
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
            com.rhnxdev.hzplayer.domain.model.OrientationMode.PORTRAIT ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_PORTRAIT
            com.rhnxdev.hzplayer.domain.model.OrientationMode.LANDSCAPE ->
                android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        }
    }

    fun onVideoStarted() {
        _uiState.update { it.copy(isVideo = true, playingVideoAsAudio = false) }
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
                audioQueue = emptyList(),
                audioQueueIndex = 0,
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
        headers: Map<String, String> = emptyMap(),
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
                audioQueue = emptyList(),
                audioQueueIndex = 0,
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
                play = { pos -> playerRepository.playUri(uri, title, isVideo = isVideo, mimeType = mimeType, resumePositionMs = pos, headers = headers) },
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
                audioQueue = listOf(audio),
                audioQueueIndex = 0,
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
                audioQueue = items,
                audioQueueIndex = startIndex.coerceIn(0, items.lastIndex),
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

    // ── A-B repeat ────────────────────────────────────────────────────────────

    private var abRepeatJob: Job? = null

    /**
     * Cycle the A-B repeat state on each tap of the A-B button:
     *   idle      → mark point A at the current position
     *   A marked  → mark point B and start looping A ↔ B
     *   looping   → clear both points (back to idle)
     * A B earlier than (or too close to) A is rejected so the range stays valid.
     */
    fun onCycleAbRepeat() {
        val s = _uiState.value
        when {
            s.abLoopStartMs == null -> {
                _uiState.update { it.copy(abLoopStartMs = position.value) }
            }
            s.abLoopEndMs == null -> {
                val end = position.value
                if (end <= s.abLoopStartMs + 500L) return
                _uiState.update { it.copy(abLoopEndMs = end) }
                startAbRepeatLoop()
            }
            else -> clearAbRepeat()
        }
    }

    /** Watch the position tick and jump back to A whenever B is reached. */
    private fun startAbRepeatLoop() {
        abRepeatJob?.cancel()
        abRepeatJob = viewModelScope.launch {
            position.collect { pos ->
                val start = _uiState.value.abLoopStartMs ?: return@collect
                val end = _uiState.value.abLoopEndMs ?: return@collect
                if (pos >= end) onSeekTo(start)
            }
        }
    }

    /** Disarm A-B repeat and clear both markers. */
    fun clearAbRepeat() {
        abRepeatJob?.cancel()
        abRepeatJob = null
        _uiState.update { it.copy(abLoopStartMs = null, abLoopEndMs = null) }
    }

    // ── Chapters ────────────────────────────────────────────────────────────

    private var chaptersLoadedForUri: String? = null

    /**
     * Probe the current video's container chapters once per URI (READY state).
     * The probe runs the native FFmpeg demuxer on IO, so it must not repeat on
     * every READY transition (buffer → ready cycles happen constantly).
     */
    private fun loadChaptersIfNeeded() {
        val state = _uiState.value
        val uri = state.currentPlaybackUri ?: return
        if (!state.isVideo || chaptersLoadedForUri == uri) return
        chaptersLoadedForUri = uri
        viewModelScope.launch {
            val chapters = MediaInfoProbe.probeChapters(appContext, uri)
            if (chapters.isNotEmpty() && _uiState.value.currentPlaybackUri == uri) {
                android.util.Log.i(TAG, "loadChapters: ${chapters.size} chapters for $uri")
                _uiState.update { it.copy(chapters = chapters) }
            }
        }
    }

    // ── Sleep timer ───────────────────────────────────────────────────

    private var sleepTimerJob: Job? = null

    /**
     * Remaining sleep-timer time (ms), ticking at 1 Hz; 0 when inactive. Kept
     * out of [uiState] (same as [position]) so the tick only recomposes the
     * countdown label, not the whole player UI.
     */
    private val _sleepTimerRemainingMs = MutableStateFlow(0L)
    val sleepTimerRemainingMs: StateFlow<Long> = _sleepTimerRemainingMs.asStateFlow()

    /** True when the active sleep timer is the "end of video" mode. */
    var sleepTimerEndOfVideo = false
        private set

    /**
     * Arm the sleep timer. [durationMs] is a fixed countdown, or
     * [SLEEP_TIMER_END_OF_VIDEO] to pause when the current video finishes.
     * Passing 0 (or a negative other than the sentinel) cancels the timer.
     */
    fun onSetSleepTimer(durationMs: Long) {
        onCancelSleepTimer()
        if (durationMs == SLEEP_TIMER_END_OF_VIDEO) {
            sleepTimerEndOfVideo = true
            sleepTimerJob = viewModelScope.launch {
                while (isActive) {
                    val duration = _uiState.value.duration
                    val remaining = (duration - position.value).coerceAtLeast(0L)
                    _sleepTimerRemainingMs.value = remaining
                    if (duration > 0 && remaining <= 1_000L) break
                    delay(1_000)
                }
                android.util.Log.i(TAG, "Sleep timer (end of video) expired — pausing")
                pause()
                onCancelSleepTimer()
            }
        } else if (durationMs > 0) {
            sleepTimerJob = viewModelScope.launch {
                val endAt = android.os.SystemClock.elapsedRealtime() + durationMs
                while (isActive) {
                    val remaining = endAt - android.os.SystemClock.elapsedRealtime()
                    if (remaining <= 0L) break
                    _sleepTimerRemainingMs.value = remaining
                    delay(1_000)
                }
                android.util.Log.i(TAG, "Sleep timer expired — pausing")
                pause()
                onCancelSleepTimer()
            }
        }
    }

    /** Disarm the sleep timer and reset the countdown. */
    fun onCancelSleepTimer() {
        sleepTimerJob?.cancel()
        sleepTimerJob = null
        sleepTimerEndOfVideo = false
        _sleepTimerRemainingMs.value = 0L
    }

    /**
     * Continue the current playback as background audio: the caller closes the
     * video screen while the singleton engine keeps playing. Flipping
     * [PlayerUiState.isVideo] off hands the session to the mini player / audio
     * UI, and [isMinimizing] stops the screen's ON_STOP handler from pausing.
     */
    fun onPlayAsAudio() {
        isMinimizing = true
        _uiState.update { it.copy(isVideo = false, playingVideoAsAudio = true, showPlaylistDrawer = false) }
    }

    /**
     * Reverse of [onPlayAsAudio]: the mini player re-opens the ongoing audio-only
     * session as full-screen video; the engine keeps playing uninterrupted.
     */
    fun onResumeAsVideo() {
        _uiState.update { it.copy(isVideo = true, playingVideoAsAudio = false) }
    }

    fun stop() {
        android.util.Log.i(TAG, "stop() called")
        positionController.saveProgressNow()
        playerRepository.stop()
        positionController.reset()
        subtitlePreferenceAppliedForUri = null
        chaptersLoadedForUri = null
        onCancelSleepTimer()
        clearAbRepeat()
        // The engine keeps its A/V delay across media; zero it so the next
        // playback starts in sync like the UI state below indicates.
        playerRepository.setAudioDelay(0)
        // Reset every per-session field so the next video starts from a clean
        // slate immediately, not only when new media replaces the old one.
        // Preference-driven fields (seekSensitivity, useSurfaceView, debugMode,
        // activeEngineType, ...) are intentionally kept.
        _uiState.update { it.copy(
            currentTitle = null,
            currentArtist = null,
            currentPlaybackUri = null,
            currentArtworkUri = null,
            isPlaying = false,
            isLoading = false,
            playingVideoAsAudio = false,
            duration = 0,
            bufferedPercentage = 0,
            playbackSpeed = 1.0f,
            subtitleTracks = emptyList(),
            audioTracks = emptyList(),
            selectedSubtitleTrack = -1,
            selectedAudioTrack = -1,
            showControls = true,
            externalSubtitles = emptyList(),
            subtitleDelayMs = 0,
            audioDelayMs = 0,
            playerLocked = false,
            errorMessage = null,
            errorKind = null,
            aspectRatioMode = com.rhnxdev.hzplayer.domain.model.AspectRatioMode.AUTO,
            videoPlaylist = emptyList(),
            currentPlaylistIndex = 0,
            showPlaylistDrawer = false,
            chapters = emptyList(),
            abLoopStartMs = null,
            abLoopEndMs = null,
            audioQueue = emptyList(),
            audioQueueIndex = 0,
            showAudioQueue = false,
            debugOverlayVisible = false,
            isVideoSurfaceActive = false,
            pendingResume = null,
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

    /** Notify the position controller that the app returned to the foreground. */
    fun onAppForeground() = positionController.onForeground()

    /** Notify the position controller that the app moved to the background. */
    fun onAppBackground() = positionController.onBackground()

    fun playNetworkUri(uri: String, title: String, isVideo: Boolean, mimeType: String? = null, headers: Map<String, String> = emptyMap()) {
        android.util.Log.i(TAG, "playNetworkUri: scheme=${uri.substringBefore("://")} uri=$uri (headersCount=${headers.size})")
        playUri(uri, title, isVideo, mimeType = mimeType, headers = headers)
    }

    fun playVideoPlaylist(items: List<VideoItem>, startIndex: Int = 0) =
        playlistController.playVideoPlaylist(items, startIndex)

    fun onPlaylistNext(): Boolean = playlistController.onPlaylistNext()

    fun onPlaylistPrevious(): Boolean = playlistController.onPlaylistPrevious()

    fun onPlaylistSelect(index: Int) = playlistController.onPlaylistSelect(index)

    fun onTogglePlaylistDrawer() = playlistController.onTogglePlaylistDrawer()

    fun clearPlaylist() = playlistController.clearPlaylist()

    // ── Audio queue ───────────────────────────────────────────────

    fun onToggleAudioQueue() {
        _uiState.update { it.copy(showAudioQueue = !it.showAudioQueue) }
    }

    fun onAudioQueueSelect(index: Int) {
        val queue = _uiState.value.audioQueue
        if (index !in queue.indices) return
        val item = queue[index]
        _uiState.update {
            it.copy(
                audioQueueIndex = index,
                currentTitle = item.title,
                currentArtist = item.artist,
                currentArtworkUri = item.albumArtUri,
                duration = item.durationMs,
                currentPlaybackUri = item.uri,
            )
        }
        playerRepository.seekToMediaItem(index)
        trackCache.markNeedsRefresh()
    }

    fun onToggleDebugOverlay() = debugController.onToggleDebugOverlay()

    /** Persist the current volume level so it can be restored next session. */
    fun saveLastVolume(volume: Float) {
        if (!saveVolumeBrightnessState.value) return
        viewModelScope.launch {
            userPreferencesRepository.setLastVolume(volume)
        }
    }

    /** Persist the current brightness level so it can be restored next session. */
    fun saveLastBrightness(brightness: Float) {
        if (!saveVolumeBrightnessState.value) return
        viewModelScope.launch {
            userPreferencesRepository.setLastBrightness(brightness)
        }
    }

    private fun observeSeekSensitivity() {
        viewModelScope.launch {
            userPreferencesRepository.seekSensitivity.collect { sensitivity ->
                _uiState.update { it.copy(seekSensitivity = sensitivity) }
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        android.util.Log.i(TAG, "onCleared() called, isShuttingDown=$isShuttingDown")
        positionController.onCleared()
        if (isShuttingDown) {
            playerRepository.stop()
        }
    }
}

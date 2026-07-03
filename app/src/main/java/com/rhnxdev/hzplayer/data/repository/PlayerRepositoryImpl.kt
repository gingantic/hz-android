package com.rhnxdev.hzplayer.data.repository

import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import androidx.media3.common.Player
import kotlinx.coroutines.ExperimentalCoroutinesApi
import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.di.ExoPlayerQualifier
import com.rhnxdev.hzplayer.di.VlcQualifier
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.domain.repository.UserPreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@OptIn(ExperimentalCoroutinesApi::class)
@Singleton
class PlayerRepositoryImpl @Inject constructor(
    @ExoPlayerQualifier private val exoPlayerEngine: IPlayerEngine,
    @VlcQualifier private val vlcEngine: IPlayerEngine,
    private val exoPlayerEngineConcrete: ExoPlayerEngine,
    private val userPreferencesRepository: UserPreferencesRepository,
) : PlayerRepository {

    // Must be declared before init blocks that use it.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    init {
        // Load the persisted engine selection asynchronously
        scope.launch {
            val savedEngine = userPreferencesRepository.activeEngine.first()
            _activeEngine.value = when (savedEngine) {
                EngineType.EXO_PLAYER -> exoPlayerEngine
                EngineType.VLC -> vlcEngine
            }
        }
    }

    private val _networkTraffic = MutableStateFlow(NetworkTraffic.DEFAULT)

    /** Backing ExoPlayer instance for `PlayerView` / service binding. */
    val exoPlayer: Player get() = exoPlayerEngineConcrete.player

    override val networkTraffic: Flow<NetworkTraffic> = _networkTraffic.asStateFlow()

    override val currentPlaybackUri: String? get() = savedPlaybackUri

    // ── Active engine management ───────────────────────────────

    private val _activeEngine = MutableStateFlow<IPlayerEngine>(exoPlayerEngine)

    override val activeEngine: IPlayerEngine get() = _activeEngine.value

    override val activeEngineType: EngineType
        get() = when (_activeEngine.value) {
            exoPlayerEngine -> EngineType.EXO_PLAYER
            else -> EngineType.VLC
        }

    override val activeEngineTypeFlow: Flow<EngineType> = _activeEngine.map { engine ->
        if (engine == exoPlayerEngine) EngineType.EXO_PLAYER else EngineType.VLC
    }

    private val _isSwitchingEngine = MutableStateFlow(false)
    override val isSwitchingEngine: Boolean get() = _isSwitchingEngine.value

    /**
     * Compose the active engine's playback state into a single [Flow].
     * When the engine is swapped mid-stream the flow transparently
     * re-subscribes to the new engine.
     */
    override val playbackStateInfo: Flow<PlayerStateInfo> = _activeEngine.flatMapLatest { engine ->
        engine.playbackState
    }

    // ── Track the last-played URI for engine switching ─────────

    private var savedPlaybackUri: String? = null
    private var savedPlaybackTitle: String? = null
    private var savedPlaybackIsVideo: Boolean = false

    // ── Network traffic tracking (process-level via TrafficStats) ─

    private var trafficPollJob: Job? = null
    private val appUid = Process.myUid()

    private fun startTrafficPolling() {
        trafficPollJob?.cancel()
        trafficPollJob = scope.launch(Dispatchers.Default) {
            var lastRx = TrafficStats.getUidRxBytes(appUid).coerceAtLeast(0)
            var smoothedSpeed = 0f
            while (isActive) {
                delay(750)
                val currentRx = TrafficStats.getUidRxBytes(appUid).coerceAtLeast(0)
                val delta = (currentRx - lastRx).coerceAtLeast(0)
                val instantSpeed = delta / 0.75f // bytes/sec
                // Exponential moving average — smooths out TrafficStats jitter
                smoothedSpeed = if (smoothedSpeed == 0f) instantSpeed
                    else smoothedSpeed * 0.3f + instantSpeed * 0.7f
                lastRx = currentRx
                _networkTraffic.value = NetworkTraffic(
                    bytesDown = currentRx,
                    speedDown = smoothedSpeed.toLong(),
                )
            }
        }
    }

    private fun stopTrafficPolling() {
        trafficPollJob?.cancel()
        trafficPollJob = null
        _networkTraffic.value = NetworkTraffic.DEFAULT
    }

    // ── Engine switching ───────────────────────────────────────

    override suspend fun switchEngine(type: EngineType) {
        if (_isSwitchingEngine.value) return
        if (activeEngineType == type) return

        _isSwitchingEngine.value = true

        try {
            // Save current state before switching
            val currentEngine = _activeEngine.value
            val savedPosition = currentEngine.getCurrentPosition()
            val savedWasPlaying = currentEngine.isPlaying()
            val savedUri = savedPlaybackUri
            val savedTitle = savedPlaybackTitle

            // Stop current engine
            currentEngine.stop()

            // Switch
            _activeEngine.value = when (type) {
                EngineType.EXO_PLAYER -> exoPlayerEngine
                EngineType.VLC -> vlcEngine
            }

            // Resume playback at saved position
            if (savedUri != null) {
                _activeEngine.value.play(savedUri, savedTitle ?: "", isVideo = savedPlaybackIsVideo)
                if (savedPosition > 0) {
                    _activeEngine.value.seekTo(savedPosition)
                }
                if (savedWasPlaying) {
                    _activeEngine.value.resume()
                } else {
                    _activeEngine.value.pause()
                }
            }

            // Persist the selection
            userPreferencesRepository.setActiveEngine(type)
        } finally {
            _isSwitchingEngine.value = false
        }
    }

    // ── Playback control ───────────────────────────────────────

    override fun playVideo(video: VideoItem) {
        savedPlaybackUri = video.uri
        savedPlaybackTitle = video.title
        savedPlaybackIsVideo = true
        startTrafficPolling()
        _activeEngine.value.play(video.uri, video.title, isVideo = true)
    }

    override fun playAudio(audio: AudioItem) {
        savedPlaybackUri = audio.uri
        savedPlaybackTitle = audio.title
        savedPlaybackIsVideo = false
        startTrafficPolling()
        _activeEngine.value.play(audio.uri, audio.title, isVideo = false)
    }

    override fun playUri(uri: String, title: String, isVideo: Boolean) {
        savedPlaybackUri = uri
        savedPlaybackTitle = title
        savedPlaybackIsVideo = isVideo
        startTrafficPolling()
        _activeEngine.value.play(uri, title, isVideo = isVideo)
    }

    override fun togglePlayPause() {
        if (_activeEngine.value.isPlaying()) {
            _activeEngine.value.pause()
        } else {
            _activeEngine.value.resume()
        }
    }

    override fun seekTo(positionMs: Long) {
        _activeEngine.value.seekTo(positionMs)
    }

    override fun skipForward(ms: Long) {
        _activeEngine.value.skipForward(ms)
    }

    override fun skipBackward(ms: Long) {
        _activeEngine.value.skipBackward(ms)
    }

    override fun setSpeed(speed: Float) {
        _activeEngine.value.setPlaybackSpeed(speed)
    }

    override fun toggleShuffle() {
        // Shuffle is ExoPlayer-specific; no-op for VLC
        if (_activeEngine.value == exoPlayerEngine) {
            exoPlayerEngineConcrete.player.shuffleModeEnabled =
                !exoPlayerEngineConcrete.player.shuffleModeEnabled
        }
    }

    override fun cycleRepeatMode() {
        // Repeat is ExoPlayer-specific; no-op for VLC
        if (_activeEngine.value == exoPlayerEngine) {
            exoPlayerEngineConcrete.player.repeatMode = when (
                exoPlayerEngineConcrete.player.repeatMode
            ) {
                Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
                Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
                Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
                else -> Player.REPEAT_MODE_OFF
            }
        }
    }

    override fun getSubtitleTracks(): List<String> = _activeEngine.value.getSubtitleTracks()

    override fun getSelectedSubtitleTrack(): Int = _activeEngine.value.getSelectedSubtitleTrack()

    override fun selectSubtitleTrack(index: Int) {
        _activeEngine.value.selectSubtitleTrack(index)
    }

    override fun addExternalSubtitle(uri: Uri): Boolean = _activeEngine.value.addExternalSubtitle(uri)

    override fun setSubtitleDelay(delayMs: Long) = _activeEngine.value.setSubtitleDelay(delayMs)

    override fun getSubtitleDelay(): Long = _activeEngine.value.getSubtitleDelay()

    override fun getAudioTracks(): List<String> = _activeEngine.value.getAudioTracks()

    override fun getSelectedAudioTrack(): Int = _activeEngine.value.getSelectedAudioTrack()

    override fun selectAudioTrack(index: Int) {
        _activeEngine.value.selectAudioTrack(index)
    }

    override fun stop() {
        savedPlaybackUri = null
        savedPlaybackTitle = null
        stopTrafficPolling()
        _activeEngine.value.stop()
    }

    override fun release() {
        exoPlayerEngine.release()
        vlcEngine.release()
    }
}

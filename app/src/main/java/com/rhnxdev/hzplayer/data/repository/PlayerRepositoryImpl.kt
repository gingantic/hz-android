package com.rhnxdev.hzplayer.data.repository

import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
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
import kotlin.jvm.JvmSuppressWildcards
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.OptIn
import kotlinx.coroutines.ExperimentalCoroutinesApi
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRepositoryImpl @Inject constructor(
    private val engines: Map<EngineType, @JvmSuppressWildcards IPlayerEngine>,
    private val userPreferencesRepository: UserPreferencesRepository,
) : PlayerRepository {

    // ponytail: process-lifetime scope — PlayerRepository is @Singleton, so this
    // never needs cancellation; the same instance lives for the app's lifetime.
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _networkTraffic = MutableStateFlow(NetworkTraffic.DEFAULT)
    private var savedPlaybackUri: String? = null
    private var trafficPollJob: Job? = null
    private val appUid = Process.myUid()

    private val _activeEngineType = MutableStateFlow(EngineType.EXO_PLAYER)

    init {
        scope.launch {
            userPreferencesRepository.activeEngine.collect { type ->
                if (engines.containsKey(type)) _activeEngineType.value = type
            }
        }
        scope.launch {
            userPreferencesRepository.decoderMode.collect { mode ->
                engine().setDecoderMode(mode)
            }
        }
        scope.launch {
            playbackStateInfo.collect { info ->
                info.currentUri?.let { uri -> savedPlaybackUri = uri }
            }
        }
    }

    private fun engine(): IPlayerEngine =
        engines[_activeEngineType.value] ?: engines.getValue(EngineType.EXO_PLAYER)

    override val networkTraffic: Flow<NetworkTraffic> = _networkTraffic.asStateFlow()
    override val currentPlaybackUri: String? get() = savedPlaybackUri
    override val activeEngine: IPlayerEngine get() = engine()
    override val availableEngines: List<EngineType> get() = engines.keys.toList()

    override val playbackStateInfo: Flow<PlayerStateInfo> =
        _activeEngineType.flatMapLatest { engine().playbackState }

    private fun startTrafficPolling() {
        trafficPollJob?.cancel()
        val uri = savedPlaybackUri ?: return
        // Only poll network traffic for remote URIs — local file playback doesn't consume network
        if (!uri.contains("://") || uri.startsWith("file://") || uri.startsWith("content://")) {
            _networkTraffic.value = NetworkTraffic.DEFAULT
            return
        }
        // Some devices don't report per-UID traffic (returns -1). Surface that once
        // instead of silently showing 0 and confusing the user.
        val initialRx = TrafficStats.getUidRxBytes(appUid)
        if (initialRx == -1L) {
            _networkTraffic.value = NetworkTraffic(unsupported = true)
            return
        }
        trafficPollJob = scope.launch(Dispatchers.Default) {
            var lastRx = initialRx
            var smoothedSpeed = 0f
            while (isActive) {
                delay(750)
                val currentRx = TrafficStats.getUidRxBytes(appUid).coerceAtLeast(0)
                val delta = (currentRx - lastRx).coerceAtLeast(0)
                val instantSpeed = delta / 0.75f
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

    override fun playVideo(video: VideoItem, resumePositionMs: Long) {
        savedPlaybackUri = video.uri
        startTrafficPolling()
        engine().play(video.uri, video.title, isVideo = true, resumePositionMs = resumePositionMs)
    }

    override fun playAudio(audio: AudioItem, resumePositionMs: Long) {
        savedPlaybackUri = audio.uri
        startTrafficPolling()
        engine().play(audio.uri, audio.title, artist = audio.artist, isVideo = false, resumePositionMs = resumePositionMs)
    }

    override fun playUri(uri: String, title: String, isVideo: Boolean, mimeType: String?, resumePositionMs: Long) {
        savedPlaybackUri = uri
        startTrafficPolling()
        engine().play(uri, title, isVideo = isVideo, mimeType = mimeType, resumePositionMs = resumePositionMs)
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        savedPlaybackUri = items.getOrNull(startIndex)?.first
        startTrafficPolling()
        engine().playPlaylist(items, startIndex, startPositionMs)
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        savedPlaybackUri = items.getOrNull(startIndex)?.uri
        startTrafficPolling()
        engine().playAudioPlaylist(items, startIndex)
    }

    override fun getCurrentMediaItemIndex(): Int = engine().getCurrentMediaItemIndex()

    override fun getMediaItemCount(): Int = engine().getMediaItemCount()

    override fun togglePlayPause() {
        if (engine().isPlaying()) engine().pause()
        else engine().resume()
    }

    override fun seekTo(positionMs: Long) = engine().seekTo(positionMs)
    override fun skipForward(ms: Long) = engine().skipForward(ms)
    override fun skipBackward(ms: Long) = engine().skipBackward(ms)
    override fun skipToNext() = engine().skipToNext()
    override fun skipToPrevious() = engine().skipToPrevious()
    override fun setSpeed(speed: Float) = engine().setPlaybackSpeed(speed)

    override fun toggleShuffle() {
        engine().setShuffleEnabled(!engine().isShuffleEnabled())
    }

    override fun cycleRepeatMode() {
        val next = when (engine().getRepeatMode()) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        engine().setRepeatMode(next)
    }

    override fun setActiveEngine(type: EngineType) {
        if (!engines.containsKey(type)) return
        if (type == _activeEngineType.value) return
        // Stop the outgoing engine but keep its instance alive for switch-back.
        engine().stop()
        _activeEngineType.value = type
        scope.launch { userPreferencesRepository.setActiveEngine(type) }
    }

    override fun getSubtitleTracks(): List<String> = engine().getSubtitleTracks()
    override fun getSelectedSubtitleTrack(): Int = engine().getSelectedSubtitleTrack()
    override fun selectSubtitleTrack(index: Int) = engine().selectSubtitleTrack(index)

    override fun loadExternalAss(uri: Uri) = engine().loadExternalAss(uri)
    override fun addExternalSubtitle(uri: Uri): Boolean = engine().addExternalSubtitle(uri)
    var subtitleTrackChangeListener: (() -> Unit)?
        get() = engine().subtitleTrackChangeListener
        set(value) { engine().subtitleTrackChangeListener = value }
    override fun setSubtitleDelay(delayMs: Long) = engine().setSubtitleDelay(delayMs)
    override fun getSubtitleDelay(): Long = engine().getSubtitleDelay()
    override fun getAudioTracks(): List<String> = engine().getAudioTracks()
    override fun getSelectedAudioTrack(): Int = engine().getSelectedAudioTrack()
    override fun selectAudioTrack(index: Int) = engine().selectAudioTrack(index)

    override fun getDebugStats(): DebugStats? = engine().getDebugStats()

    override fun stop() {
        savedPlaybackUri = null
        stopTrafficPolling()
        engine().stop()
    }

    override fun clearError() {
        engine().clearError()
    }

    override fun retry() {
        engine().retry()
    }

    override fun release() {
        engines.values.forEach { it.release() }
    }
}

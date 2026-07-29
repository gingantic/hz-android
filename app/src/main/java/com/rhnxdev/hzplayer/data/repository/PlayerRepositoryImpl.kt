package com.rhnxdev.hzplayer.data.repository

import android.content.Context
import android.content.Intent
import android.net.TrafficStats
import android.net.Uri
import android.util.Log
import android.os.Process
import com.rhnxdev.hzplayer.data.datasource.player.MediaPlaybackService
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
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject

@OptIn(ExperimentalCoroutinesApi::class)
class PlayerRepositoryImpl @Inject constructor(
    @param:ApplicationContext private val context: Context,
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

    companion object {
        private const val TAG = "PlayerRepository"
    }

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

    /**
     * Start [MediaPlaybackService] so the system MediaSession (notification /
     * lock-screen / Bluetooth controls) is published for this playback. Safe to
     * call repeatedly — startService on a running service is a no-op. Play is
     * always user-initiated with the app in the foreground, so a plain
     * startService is allowed; Media3 promotes the service to foreground with
     * the media notification once the player is actually playing.
     */
    private fun startPlaybackService() {
        try {
            context.startService(Intent(context, MediaPlaybackService::class.java))
        } catch (e: IllegalStateException) {
            // App unexpectedly in background — playback still works in-app,
            // only the system controls are unavailable until next play.
            Log.w(TAG, "startPlaybackService failed: ${e.message}")
        }
    }

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
        Log.i(TAG, "playVideo: title=${video.title} resumeMs=$resumePositionMs")
        savedPlaybackUri = video.uri
        startTrafficPolling()
        startPlaybackService()
        engine().play(video.uri, video.title, isVideo = true, resumePositionMs = resumePositionMs)
    }

    override fun playAudio(audio: AudioItem, resumePositionMs: Long) {
        Log.i(TAG, "playAudio: title=${audio.title} resumeMs=$resumePositionMs")
        savedPlaybackUri = audio.uri
        startTrafficPolling()
        startPlaybackService()
        engine().play(audio.uri, audio.title, artist = audio.artist, isVideo = false, resumePositionMs = resumePositionMs, artworkUri = audio.albumArtUri)
    }

    override fun playUri(uri: String, title: String, isVideo: Boolean, mimeType: String?, resumePositionMs: Long, headers: Map<String, String>) {
        Log.i(TAG, "playUri: title=$title isVideo=$isVideo mimeType=$mimeType resumeMs=$resumePositionMs headers=${headers.size}")
        savedPlaybackUri = uri
        startTrafficPolling()
        startPlaybackService()
        engine().play(uri, title, isVideo = isVideo, mimeType = mimeType, resumePositionMs = resumePositionMs, headers = headers)
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        Log.i(TAG, "playPlaylist: items=${items.size} startIndex=$startIndex startPosMs=$startPositionMs")
        savedPlaybackUri = items.getOrNull(startIndex)?.first
        startTrafficPolling()
        startPlaybackService()
        engine().playPlaylist(items, startIndex, startPositionMs)
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        Log.i(TAG, "playAudioPlaylist: items=${items.size} startIndex=$startIndex")
        savedPlaybackUri = items.getOrNull(startIndex)?.uri
        startTrafficPolling()
        startPlaybackService()
        engine().playAudioPlaylist(items, startIndex)
    }

    override fun getCurrentMediaItemIndex(): Int = engine().getCurrentMediaItemIndex()

    override fun getMediaItemCount(): Int = engine().getMediaItemCount()

    override fun seekToMediaItem(index: Int) = engine().seekToMediaItem(index)

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
        Log.i(TAG, "setActiveEngine: ${_activeEngineType.value} -> $type")
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
    override fun setAudioDelay(delayMs: Long) = engine().setAudioDelay(delayMs)
    override fun getAudioDelay(): Long = engine().getAudioDelay()
    override fun getAudioTracks(): List<String> = engine().getAudioTracks()
    override fun getSelectedAudioTrack(): Int = engine().getSelectedAudioTrack()
    override fun selectAudioTrack(index: Int) = engine().selectAudioTrack(index)

    override fun getDebugStats(): DebugStats? = engine().getDebugStats()

    override fun stop() {
        Log.i(TAG, "stop")
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

package com.rhnxdev.hzplayer.data.repository

import android.net.TrafficStats
import android.net.Uri
import android.os.Process
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.data.datasource.player.ExoPlayerEngine
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val exoPlayerEngine: ExoPlayerEngine,
) : PlayerRepository {

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val _networkTraffic = MutableStateFlow(NetworkTraffic.DEFAULT)
    private var savedPlaybackUri: String? = null
    private var trafficPollJob: Job? = null
    private val appUid = Process.myUid()

    init {
        scope.launch {
            playbackStateInfo.collect { info ->
                info.currentUri?.let { uri ->
                    savedPlaybackUri = uri
                }
            }
        }
    }

    val exoPlayer: Player get() = exoPlayerEngine.player

    override val networkTraffic: Flow<NetworkTraffic> = _networkTraffic.asStateFlow()
    override val currentPlaybackUri: String? get() = savedPlaybackUri
    override val activeEngine: IPlayerEngine get() = exoPlayerEngine

    override val playbackStateInfo: Flow<PlayerStateInfo> = exoPlayerEngine.playbackState

    val subtitleCues: StateFlow<List<androidx.media3.common.text.Cue>> get() = exoPlayerEngine.subtitleCues
    val displayNeedsSurfaceView: StateFlow<Boolean> get() = exoPlayerEngine.displayNeedsSurfaceView

    private fun startTrafficPolling() {
        trafficPollJob?.cancel()
        val uri = savedPlaybackUri ?: return
        // Only poll network traffic for remote URIs — local file playback doesn't consume network
        if (!uri.contains("://") || uri.startsWith("file://") || uri.startsWith("content://")) {
            _networkTraffic.value = NetworkTraffic.DEFAULT
            return
        }
        trafficPollJob = scope.launch(Dispatchers.Default) {
            var lastRx = TrafficStats.getUidRxBytes(appUid).coerceAtLeast(0)
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

    override fun playVideo(video: VideoItem) {
        savedPlaybackUri = video.uri
        startTrafficPolling()
        exoPlayerEngine.play(video.uri, video.title, isVideo = true)
    }

    override fun playAudio(audio: AudioItem) {
        savedPlaybackUri = audio.uri
        startTrafficPolling()
        exoPlayerEngine.play(audio.uri, audio.title, artist = audio.artist, isVideo = false)
    }

    override fun playUri(uri: String, title: String, isVideo: Boolean) {
        savedPlaybackUri = uri
        startTrafficPolling()
        exoPlayerEngine.play(uri, title, isVideo = isVideo)
    }

    override fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int, startPositionMs: Long) {
        savedPlaybackUri = items.getOrNull(startIndex)?.first
        startTrafficPolling()
        exoPlayerEngine.playPlaylist(items, startIndex, startPositionMs)
    }

    override fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int) {
        savedPlaybackUri = items.getOrNull(startIndex)?.uri
        startTrafficPolling()
        exoPlayerEngine.playAudioPlaylist(items, startIndex)
    }

    override fun getCurrentMediaItemIndex(): Int =
        exoPlayerEngine.player.currentMediaItemIndex

    override fun getMediaItemCount(): Int =
        exoPlayerEngine.player.mediaItemCount

    override fun togglePlayPause() {
        if (exoPlayerEngine.isPlaying()) exoPlayerEngine.pause()
        else exoPlayerEngine.resume()
    }

    override fun seekTo(positionMs: Long) = exoPlayerEngine.seekTo(positionMs)
    override fun skipForward(ms: Long) = exoPlayerEngine.skipForward(ms)
    override fun skipBackward(ms: Long) = exoPlayerEngine.skipBackward(ms)
    override fun setSpeed(speed: Float) = exoPlayerEngine.setPlaybackSpeed(speed)

    override fun toggleShuffle() {
        exoPlayerEngine.player.shuffleModeEnabled = !exoPlayerEngine.player.shuffleModeEnabled
    }

    override fun cycleRepeatMode() {
        exoPlayerEngine.player.repeatMode = when (exoPlayerEngine.player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun getSubtitleTracks(): List<String> = exoPlayerEngine.getSubtitleTracks()
    override fun getSelectedSubtitleTrack(): Int = exoPlayerEngine.getSelectedSubtitleTrack()
    override fun selectSubtitleTrack(index: Int) = exoPlayerEngine.selectSubtitleTrack(index)
    override fun addExternalSubtitle(uri: Uri): Boolean = exoPlayerEngine.addExternalSubtitle(uri)
    override fun setSubtitleDelay(delayMs: Long) = exoPlayerEngine.setSubtitleDelay(delayMs)
    override fun getSubtitleDelay(): Long = exoPlayerEngine.getSubtitleDelay()
    override fun getAudioTracks(): List<String> = exoPlayerEngine.getAudioTracks()
    override fun getSelectedAudioTrack(): Int = exoPlayerEngine.getSelectedAudioTrack()
    override fun selectAudioTrack(index: Int) = exoPlayerEngine.selectAudioTrack(index)

    override fun stop() {
        savedPlaybackUri = null
        stopTrafficPolling()
        exoPlayerEngine.stop()
    }

    override fun release() {
        exoPlayerEngine.release()
    }
}

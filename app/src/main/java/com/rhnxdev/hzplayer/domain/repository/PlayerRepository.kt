package com.rhnxdev.hzplayer.domain.repository

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.DebugStats
import com.rhnxdev.hzplayer.domain.model.EqualizerInfo
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

interface PlayerRepository {
    val playbackStateInfo: Flow<PlayerStateInfo>
    val networkTraffic: Flow<NetworkTraffic>
    val currentPlaybackUri: String?
    val activeEngine: IPlayerEngine
    /** Engines registered via Hilt multibinding (for the settings selector). */
    val availableEngines: List<EngineType>

    fun playVideo(video: VideoItem, resumePositionMs: Long = 0)
    fun playAudio(audio: AudioItem, resumePositionMs: Long = 0)
    fun playUri(uri: String, title: String, isVideo: Boolean = false, mimeType: String? = null, resumePositionMs: Long = 0, headers: Map<String, String> = emptyMap())
    fun playPlaylist(items: List<Pair<String, String>>, startIndex: Int = 0, startPositionMs: Long = 0)
    fun playAudioPlaylist(items: List<AudioItem>, startIndex: Int = 0)
    fun getCurrentMediaItemIndex(): Int
    fun getMediaItemCount(): Int
    fun seekToMediaItem(index: Int)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun setScrubbing(isScrubbing: Boolean)
    fun skipForward(ms: Long = 10000)
    fun skipBackward(ms: Long = 10000)
    fun skipToNext()
    fun skipToPrevious()
    fun setSpeed(speed: Float)
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun setActiveEngine(type: EngineType)
    fun getDebugStats(): DebugStats?
    fun getSubtitleTracks(): List<String>
    fun getSelectedSubtitleTrack(): Int
    fun selectSubtitleTrack(index: Int)
    fun loadExternalAss(uri: Uri)
    fun addExternalSubtitle(uri: Uri): Boolean
    fun setSubtitleDelay(delayMs: Long)
    fun getSubtitleDelay(): Long
    fun setAudioDelay(delayMs: Long)
    fun getAudioDelay(): Long
    fun getEqualizerState(): StateFlow<EqualizerInfo>?
    fun setEqualizerEnabled(enabled: Boolean)
    fun setEqualizerBandLevel(band: Int, levelMb: Int)
    fun applyEqualizerPreset(preset: Int)
    fun resetEqualizerBands()
    fun setBassBoostStrength(strength: Int)
    fun setLoudnessGain(gainMb: Int)
    fun getAudioTracks(): List<String>
    fun getSelectedAudioTrack(): Int
    fun selectAudioTrack(index: Int)
    fun stop()
    fun clearError()
    fun retry()
    fun release()
}

package com.rhnxdev.hzplayer.domain.repository

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.player.EngineType
import com.rhnxdev.hzplayer.domain.player.IPlayerEngine
import kotlinx.coroutines.flow.Flow

interface PlayerRepository {
    val playbackStateInfo: Flow<PlayerStateInfo>
    val networkTraffic: Flow<NetworkTraffic>
    val currentPlaybackUri: String?

    /** A flow of the active engine type. */
    val activeEngineTypeFlow: Flow<EngineType>

    /** The currently active playback engine. */
    val activeEngine: IPlayerEngine

    /** The type of the currently active engine. */
    val activeEngineType: EngineType

    /** Switch the active playback engine (persisted). */
    suspend fun switchEngine(type: EngineType)

    /** Whether [switchEngine] is currently in progress. */
    val isSwitchingEngine: Boolean

    fun playVideo(video: VideoItem)
    fun playAudio(audio: AudioItem)
    fun playUri(uri: String, title: String, isVideo: Boolean = false)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipForward(ms: Long = 10000)
    fun skipBackward(ms: Long = 10000)
    fun setSpeed(speed: Float)
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun getSubtitleTracks(): List<String>
    fun getSelectedSubtitleTrack(): Int
    fun selectSubtitleTrack(index: Int)
    fun addExternalSubtitle(uri: Uri): Boolean
    fun setSubtitleDelay(delayMs: Long)
    fun getSubtitleDelay(): Long
    fun getAudioTracks(): List<String>
    fun getSelectedAudioTrack(): Int
    fun selectAudioTrack(index: Int)
    fun stop()
    fun release()
}

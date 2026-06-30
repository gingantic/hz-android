package com.rhnxdev.hzplayer.domain.repository

import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import kotlinx.coroutines.flow.Flow

interface PlayerRepository {
    val playbackStateInfo: Flow<PlayerStateInfo>

    fun playVideo(video: VideoItem)
    fun playAudio(audio: AudioItem)
    fun playUri(uri: String, title: String)
    fun togglePlayPause()
    fun seekTo(positionMs: Long)
    fun skipForward(ms: Long = 10000)
    fun skipBackward(ms: Long = 10000)
    fun setSpeed(speed: Float)
    fun toggleShuffle()
    fun cycleRepeatMode()
    fun stop()
    fun release()
}

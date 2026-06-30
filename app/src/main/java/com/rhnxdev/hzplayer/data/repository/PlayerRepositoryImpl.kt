package com.rhnxdev.hzplayer.data.repository

import androidx.media3.common.Player
import com.rhnxdev.hzplayer.data.datasource.player.MediaPlayerHolder
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class PlayerRepositoryImpl @Inject constructor(
    private val playerHolder: MediaPlayerHolder,
) : PlayerRepository {

    override val playbackStateInfo: Flow<PlayerStateInfo> = playerHolder.playbackStateInfo

    override fun playVideo(video: VideoItem) {
        val mediaItem = playerHolder.buildMediaItem(video.uri, video.title)
        playerHolder.player.setMediaItem(mediaItem)
        playerHolder.player.prepare()
        playerHolder.player.play()
    }

    override fun playAudio(audio: AudioItem) {
        val mediaItem = playerHolder.buildMediaItem(audio.uri, audio.title)
        playerHolder.player.setMediaItem(mediaItem)
        playerHolder.player.prepare()
        playerHolder.player.play()
    }

    override fun playUri(uri: String, title: String) {
        val mediaItem = playerHolder.buildMediaItem(uri, title)
        playerHolder.player.setMediaItem(mediaItem)
        playerHolder.player.prepare()
        playerHolder.player.play()
    }

    override fun togglePlayPause() {
        if (playerHolder.player.isPlaying) {
            playerHolder.player.pause()
        } else {
            playerHolder.player.play()
        }
    }

    override fun seekTo(positionMs: Long) {
        playerHolder.player.seekTo(positionMs)
    }

    override fun skipForward(ms: Long) {
        val newPosition = (playerHolder.player.currentPosition + ms)
            .coerceAtMost(playerHolder.player.duration.coerceAtLeast(0))
        playerHolder.player.seekTo(newPosition)
    }

    override fun skipBackward(ms: Long) {
        val newPosition = (playerHolder.player.currentPosition - ms).coerceAtLeast(0)
        playerHolder.player.seekTo(newPosition)
    }

    override fun setSpeed(speed: Float) {
        playerHolder.updateSpeed(speed)
    }

    override fun toggleShuffle() {
        playerHolder.player.shuffleModeEnabled = !playerHolder.player.shuffleModeEnabled
    }

    override fun cycleRepeatMode() {
        playerHolder.player.repeatMode = when (playerHolder.player.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            Player.REPEAT_MODE_ONE -> Player.REPEAT_MODE_OFF
            else -> Player.REPEAT_MODE_OFF
        }
    }

    override fun stop() {
        playerHolder.player.stop()
    }

    override fun release() {
        playerHolder.release()
    }
}

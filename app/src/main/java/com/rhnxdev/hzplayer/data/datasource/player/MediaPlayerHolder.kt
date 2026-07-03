package com.rhnxdev.hzplayer.data.datasource.player

import android.content.Context
import androidx.media3.common.AudioAttributes
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.PlayerStateInfo
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class MediaPlayerHolder @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    val player: ExoPlayer = ExoPlayer.Builder(context)
        .setAudioAttributes(AudioAttributes.DEFAULT, true)
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _playbackStateInfo = MutableStateFlow(PlayerStateInfo())
    val playbackStateInfo: StateFlow<PlayerStateInfo> = _playbackStateInfo.asStateFlow()

    init {
        player.addListener(
            object : Player.Listener {
                override fun onPlaybackStateChanged(state: Int) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = when (state) {
                            Player.STATE_IDLE -> PlayerState.IDLE
                            Player.STATE_BUFFERING -> PlayerState.BUFFERING
                            Player.STATE_READY -> PlayerState.READY
                            Player.STATE_ENDED -> PlayerState.ENDED
                            else -> PlayerState.IDLE
                        },
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0),
                    )
                }

                override fun onPlayerError(error: androidx.media3.common.PlaybackException) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        state = PlayerState.ERROR,
                        isPlaying = false,
                        errorMessage = when (error.errorCode) {
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
                            androidx.media3.common.PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
                            -> "Network error — check your connection"
                            else -> "Playback error: ${error.localizedMessage ?: "unknown"}"
                        },
                    )
                }

                override fun onIsPlayingChanged(isPlaying: Boolean) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        isPlaying = isPlaying,
                    )
                }

                override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        shuffleModeEnabled = shuffleModeEnabled,
                    )
                }

                override fun onRepeatModeChanged(repeatMode: Int) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        repeatMode = when (repeatMode) {
                            Player.REPEAT_MODE_ONE -> RepeatMode.ONE
                            Player.REPEAT_MODE_ALL -> RepeatMode.ALL
                            else -> RepeatMode.NONE
                        },
                    )
                }

                // Bug 2 fix: update bufferedPosition on every event, not just state changes.
                // Keeps the StateFlow snapshot fresh for the position poller (important for
                // HLS/DASH where buffer fills continuously, not just at state transitions).
                override fun onEvents(player: Player, events: Player.Events) {
                    _playbackStateInfo.value = _playbackStateInfo.value.copy(
                        bufferedPosition = player.bufferedPosition.coerceAtLeast(0),
                    )
                }
            },
        )
    }

    fun updatePosition(positionMs: Long, durationMs: Long, bufferedMs: Long) {
        _playbackStateInfo.value = _playbackStateInfo.value.copy(
            currentPosition = positionMs,
            duration = durationMs,
            bufferedPosition = bufferedMs,
        )
    }

    fun updateSpeed(speed: Float) {
        player.setPlaybackSpeed(speed)
        _playbackStateInfo.value = _playbackStateInfo.value.copy(
            playbackSpeed = speed,
        )
    }

    fun buildMediaItem(uri: String, title: String): MediaItem {
        return MediaItem.Builder()
            .setUri(uri)
            .setMediaMetadata(
                androidx.media3.common.MediaMetadata.Builder()
                    .setTitle(title)
                    .build(),
            )
            .build()
    }

    fun release() {
        player.release()
    }
}

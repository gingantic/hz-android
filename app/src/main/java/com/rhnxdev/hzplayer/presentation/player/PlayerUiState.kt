package com.rhnxdev.hzplayer.presentation.player

import android.net.Uri
import com.rhnxdev.hzplayer.domain.model.NetworkTraffic
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.model.SubtitleStyle
import com.rhnxdev.hzplayer.domain.player.EngineType

data class PlayerUiState(
    val currentTitle: String? = null,
    val currentArtist: String? = null,
    val currentPlaybackUri: String? = null,
    val isVideo: Boolean = false,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPercentage: Int = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleMode: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val subtitleTracks: List<String> = emptyList(),
    val audioTracks: List<String> = emptyList(),
    val selectedSubtitleTrack: Int = -1,
    val selectedAudioTrack: Int = -1,
    val showControls: Boolean = true,
    val activeEngineType: EngineType = EngineType.EXO_PLAYER,
    val externalSubtitles: List<Pair<String, Uri>> = emptyList(),
    val subtitleDelayMs: Long = 0,
    val subtitleStyle: SubtitleStyle = SubtitleStyle.DEFAULT,
    val playerLocked: Boolean = false,
    val errorMessage: String? = null,
    val networkTraffic: NetworkTraffic = NetworkTraffic.DEFAULT,
)

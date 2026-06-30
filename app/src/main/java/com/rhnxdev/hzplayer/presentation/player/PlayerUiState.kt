package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.domain.model.RepeatMode

data class PlayerUiState(
    val currentTitle: String? = null,
    val currentArtist: String? = null,
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
)

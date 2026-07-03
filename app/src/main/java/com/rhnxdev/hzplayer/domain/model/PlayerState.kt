package com.rhnxdev.hzplayer.domain.model

enum class PlayerState {
    IDLE,
    BUFFERING,
    READY,
    ENDED,
    ERROR,
}

data class PlayerStateInfo(
    val state: PlayerState = PlayerState.IDLE,
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0,
    val duration: Long = 0,
    val bufferedPosition: Long = 0,
    val playbackSpeed: Float = 1.0f,
    val shuffleModeEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.NONE,
    val errorMessage: String? = null,
)

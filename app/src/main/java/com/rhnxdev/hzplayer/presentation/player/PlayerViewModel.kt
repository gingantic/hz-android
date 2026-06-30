package com.rhnxdev.hzplayer.presentation.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.rhnxdev.hzplayer.data.datasource.player.MediaPlayerHolder
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.model.PlayerState
import com.rhnxdev.hzplayer.domain.model.RepeatMode
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val playerRepository: PlayerRepository,
    private val playerHolder: MediaPlayerHolder,
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var positionUpdateJob: Job? = null

    fun getExoPlayer(): Player = playerHolder.player

    init {
        observePlaybackState()
        startPositionUpdates()
    }

    private fun observePlaybackState() {
        viewModelScope.launch {
            playerRepository.playbackStateInfo.collect { info ->
                _uiState.update { state ->
                    state.copy(
                        isPlaying = info.isPlaying,
                        isLoading = info.state == PlayerState.BUFFERING,
                        playbackSpeed = info.playbackSpeed,
                        shuffleMode = info.shuffleModeEnabled,
                        repeatMode = info.repeatMode,
                    )
                }
            }
        }
    }

    private fun startPositionUpdates() {
        positionUpdateJob?.cancel()
        positionUpdateJob = viewModelScope.launch {
            while (isActive) {
                delay(250)
                _uiState.update { state ->
                    state.copy(
                        currentPosition = playerHolder.player.currentPosition,
                        duration = playerHolder.player.duration.coerceAtLeast(0),
                    )
                }
            }
        }
    }

    fun onPlayPause() {
        playerRepository.togglePlayPause()
    }

    fun playAudio(audio: AudioItem) {
        _uiState.update {
            it.copy(
                currentTitle = audio.title,
                currentArtist = audio.artist,
                isPlaying = true,
                duration = audio.durationMs,
            )
        }
        playerHolder.buildMediaItem(audio.uri, audio.title).also { item ->
            playerHolder.player.setMediaItem(item)
            playerHolder.player.prepare()
            playerHolder.player.play()
        }
    }

    fun onSeekTo(positionMs: Long) {
        playerRepository.seekTo(positionMs)
    }

    fun onSkipForward() {
        playerRepository.skipForward(10000)
    }

    fun onSkipBackward() {
        playerRepository.skipBackward(10000)
    }

    fun onSetSpeed(speed: Float) {
        playerRepository.setSpeed(speed)
        _uiState.update { it.copy(playbackSpeed = speed) }
    }

    fun onToggleShuffle() {
        playerRepository.toggleShuffle()
    }

    fun onCycleRepeatMode() {
        playerRepository.cycleRepeatMode()
        val current = _uiState.value.repeatMode
        val next = when (current) {
            RepeatMode.NONE -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.NONE
        }
        _uiState.update { it.copy(repeatMode = next) }
    }

    fun stop() {
        playerRepository.stop()
        _uiState.update { it.copy(currentTitle = null, currentArtist = null, isPlaying = false) }
    }

    fun onToggleControls() {
        _uiState.update { it.copy(showControls = !it.showControls) }
    }

    fun onShowControls() {
        _uiState.update { it.copy(showControls = true) }
    }

    fun onHideControls() {
        _uiState.update { it.copy(showControls = false) }
    }

    fun onSeekBy(deltaMs: Long) {
        if (deltaMs >= 0) playerRepository.skipForward(deltaMs)
        else playerRepository.skipBackward(-deltaMs)
    }

    override fun onCleared() {
        super.onCleared()
        positionUpdateJob?.cancel()
        playerRepository.release()
    }
}

package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Caches the current subtitle/audio track lists and their selected indices so the
 * player UI isn't re-querying the engine on every recompose.
 *
 * Track queries only return data once ExoPlayer reaches READY, so producers call
 * [markNeedsRefresh] when starting new media and [refreshIfNeeded] is invoked from
 * the playback-state observer once READY arrives.
 *
 * Split out of [PlayerViewModel] purely to shrink it — behaviour is unchanged.
 */
internal class PlayerTrackCache(
    private val playerRepository: PlayerRepository,
    private val uiState: MutableStateFlow<PlayerUiState>,
) {
    private var cachedSubtitleTracks: List<String> = emptyList()
    private var cachedSelectedSubtitle: Int = -1
    private var cachedAudioTracks: List<String> = emptyList()
    private var cachedSelectedAudio: Int = -1
    private var trackRefreshNeeded = false

    /** Flag that a track refresh is required once the engine next reaches READY. */
    fun markNeedsRefresh() {
        trackRefreshNeeded = true
    }

    /** Called from the playback-state observer on READY; refreshes only if pending. */
    fun refreshIfNeeded() {
        if (trackRefreshNeeded) refresh()
    }

    fun refresh() {
        trackRefreshNeeded = false
        cachedSubtitleTracks = playerRepository.getSubtitleTracks()
        cachedSelectedSubtitle = playerRepository.getSelectedSubtitleTrack()
        cachedAudioTracks = playerRepository.getAudioTracks()
        cachedSelectedAudio = playerRepository.getSelectedAudioTrack()
        uiState.update {
            it.copy(
                subtitleTracks = cachedSubtitleTracks,
                selectedSubtitleTrack = cachedSelectedSubtitle,
                audioTracks = cachedAudioTracks,
                selectedAudioTrack = cachedSelectedAudio,
            )
        }
    }

    fun selectSubtitleTrack(index: Int) {
        playerRepository.selectSubtitleTrack(index)
        cachedSubtitleTracks = playerRepository.getSubtitleTracks()
        cachedSelectedSubtitle = playerRepository.getSelectedSubtitleTrack()
        uiState.update { state ->
            state.copy(
                subtitleTracks = cachedSubtitleTracks,
                selectedSubtitleTrack = cachedSelectedSubtitle,
            )
        }
    }

    fun selectAudioTrack(index: Int) {
        playerRepository.selectAudioTrack(index)
        cachedAudioTracks = playerRepository.getAudioTracks()
        cachedSelectedAudio = playerRepository.getSelectedAudioTrack()
        uiState.update { state ->
            state.copy(
                audioTracks = cachedAudioTracks,
                selectedAudioTrack = cachedSelectedAudio,
            )
        }
    }
}

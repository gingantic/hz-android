package com.rhnxdev.hzplayer.presentation.player

import com.rhnxdev.hzplayer.domain.model.VideoItem
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

/**
 * Owns the video playlist: loading a playlist, next/previous/select navigation,
 * and the playlist drawer toggle.
 *
 * Split out of [PlayerViewModel] purely to shrink it — behaviour is unchanged.
 */
internal class PlayerPlaylistController(
    private val playerRepository: PlayerRepository,
    private val uiState: MutableStateFlow<PlayerUiState>,
    private val trackCache: PlayerTrackCache,
) {
    fun playVideoPlaylist(items: List<VideoItem>, startIndex: Int = 0) {
        if (items.isEmpty()) return
        val item = items[startIndex.coerceIn(0, items.lastIndex)]
        uiState.update { state ->
            state.copy(
                currentTitle = item.title,
                currentArtist = null,
                isVideo = true,
                isPlaying = true,
                isLoading = true,
                duration = item.durationMs,
                currentPlaybackUri = item.uri,
                videoPlaylist = items,
                currentPlaylistIndex = startIndex,
                showPlaylistDrawer = false,
            )
        }
        val playlistItems: List<Pair<String, String>> = items.map { it.uri to it.title }
        playerRepository.playPlaylist(playlistItems, startIndex, 0L)
        trackCache.markNeedsRefresh()
    }

    fun onPlaylistNext(): Boolean {
        val playlist = uiState.value.videoPlaylist
        if (playlist.isEmpty()) return false
        val nextIndex = uiState.value.currentPlaylistIndex + 1
        if (nextIndex >= playlist.size) return false
        moveTo(nextIndex, closeDrawer = false)
        return true
    }

    fun onPlaylistPrevious(): Boolean {
        val playlist = uiState.value.videoPlaylist
        if (playlist.isEmpty()) return false
        val prevIndex = uiState.value.currentPlaylistIndex - 1
        if (prevIndex < 0) return false
        moveTo(prevIndex, closeDrawer = false)
        return true
    }

    fun onPlaylistSelect(index: Int) {
        if (index !in uiState.value.videoPlaylist.indices) return
        moveTo(index, closeDrawer = true)
    }

    /**
     * Move to [index] in the current playlist: update the now-playing fields, then
     * either ask the engine to switch (it already holds the whole playlist) or
     * re-hand it the playlist starting at [index]. [closeDrawer] is set when the
     * user picked a track directly rather than stepping with next/previous.
     */
    private fun moveTo(index: Int, closeDrawer: Boolean) {
        val playlist = uiState.value.videoPlaylist
        val item = playlist[index]
        uiState.update {
            it.copy(
                currentPlaylistIndex = index,
                currentTitle = item.title,
                currentPlaybackUri = item.uri,
                duration = item.durationMs,
                showPlaylistDrawer = if (closeDrawer) false else it.showPlaylistDrawer,
            )
        }
        if (playerRepository.getMediaItemCount() > 1) {
            playerRepository.seekToMediaItem(index)
        } else {
            playerRepository.playPlaylist(playlist.map { it.uri to it.title }, index, 0L)
        }
        trackCache.markNeedsRefresh()
    }

    fun onTogglePlaylistDrawer() {
        uiState.update {
            val opening = !it.showPlaylistDrawer
            it.copy(
                showPlaylistDrawer = opening,
                // Opening the drawer hides the HUD; hiding the HUD also hides the
                // system bars (nav bar) via the showControls → systemBars sync in the screen.
                showControls = if (opening) false else it.showControls,
            )
        }
    }

    fun clearPlaylist() {
        uiState.update { it.copy(videoPlaylist = emptyList(), showPlaylistDrawer = false) }
    }
}

package com.rhnxdev.hzplayer.presentation.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioBrowserViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioBrowserUiState())
    val uiState: StateFlow<AudioBrowserUiState> = _uiState.asStateFlow()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            // Simulate async loading of preview data
            _uiState.update { it.copy(isLoadingSongs = true, isLoadingAlbums = true, isLoadingArtists = true) }

            delay(400) // Brief delay so shimmer is visible

            _uiState.update {
                it.copy(
                    songs = PreviewMedia.songs,
                    albums = PreviewMedia.albums,
                    artists = PreviewMedia.artists,
                    filteredSongs = PreviewMedia.songs,
                    isLoadingSongs = false,
                    isLoadingAlbums = false,
                    isLoadingArtists = false,
                )
            }

            // Future: wire to audioRepository
            // audioRepository.getAllSongs().collect { songs -> updateSongs(songs) }
        }
    }

    fun onTabSelected(tab: AudioTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun onSearchToggle() {
        _uiState.update {
            it.copy(
                searchQuery = "",
                isSearchActive = true,
                filteredSongs = it.songs,
            )
        }
    }

    fun onSearchQueryChanged(query: String) {
        _uiState.update {
            it.copy(
                searchQuery = query,
                isSearchActive = true,
                filteredSongs = if (query.isBlank()) it.songs
                else it.songs.filter { song ->
                    song.title.contains(query, ignoreCase = true) ||
                        (song.artist?.contains(query, ignoreCase = true) == true) ||
                        (song.album?.contains(query, ignoreCase = true) == true)
                },
            )
        }
    }

    fun onClearSearch() {
        _uiState.update {
            it.copy(
                searchQuery = null,
                isSearchActive = false,
                filteredSongs = it.songs,
            )
        }
    }

    fun onSongClicked(song: AudioItem) {
        // Future: open player
    }

    fun onAlbumClicked(album: Album) {
        // Future: show album detail
    }

    fun onArtistClicked(artist: Artist) {
        // Future: show artist detail
    }
}

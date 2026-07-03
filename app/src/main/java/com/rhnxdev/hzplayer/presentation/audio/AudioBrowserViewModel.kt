package com.rhnxdev.hzplayer.presentation.audio

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.core.components.SearchDelegate
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import com.rhnxdev.hzplayer.presentation.preview.PreviewMedia
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

@HiltViewModel
class AudioBrowserViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioBrowserUiState())
    val uiState: StateFlow<AudioBrowserUiState> = _uiState.asStateFlow()

    /** Reusable search state holder. */
    val search = SearchDelegate()

    init {
        loadAll()
    }

    private fun loadAll() {
        viewModelScope.launch {
            _uiState.update {
                it.copy(isLoadingSongs = true, isLoadingAlbums = true, isLoadingArtists = true)
            }

            // Launch parallel collection for songs, albums, and artists
            val songJob = launch {
                audioRepository.getAllSongs()
                    .catch { /* fallback handled below */ }
                    .collect { songs ->
                        if (songs.isNotEmpty()) {
                            _uiState.update {
                                it.copy(songs = songs, filteredSongs = songs, isLoadingSongs = false)
                            }
                        }
                    }
            }

            val albumJob = launch {
                audioRepository.getAlbums()
                    .catch { /* fallback handled below */ }
                    .collect { albums ->
                        if (albums.isNotEmpty()) {
                            _uiState.update { it.copy(albums = albums, isLoadingAlbums = false) }
                        }
                    }
            }

            val artistJob = launch {
                audioRepository.getArtists()
                    .catch { /* fallback handled below */ }
                    .collect { artists ->
                        if (artists.isNotEmpty()) {
                            _uiState.update { it.copy(artists = artists, isLoadingArtists = false) }
                        }
                    }
            }

            // If after 10s any collection is still empty, fall back to preview
            launch {
                kotlinx.coroutines.delay(10_000)
                val state = _uiState.value
                if (state.isLoadingSongs || state.isLoadingAlbums || state.isLoadingArtists) {
                    applyPreviewFallback()
                }
            }
        }
    }

    private fun applyPreviewFallback() {
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
    }

    fun onTabSelected(tab: AudioTab) {
        _uiState.update { it.copy(currentTab = tab) }
    }

    fun onSearchToggle() {
        search.toggle()
        _uiState.update { it.copy(filteredSongs = it.songs) }
    }

    fun onSearchQueryChanged(query: String) {
        search.queryChanged(query)
        _uiState.update {
            it.copy(
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
        search.clear()
        _uiState.update { it.copy(filteredSongs = it.songs) }
    }

    fun onSongClicked(song: AudioItem) {
        playerRepository.playAudio(song)
    }

    fun onAlbumClicked(album: Album) {
        // Future: show album detail
    }

    fun onArtistClicked(artist: Artist) {
        // Future: show artist detail
    }

    fun onRetry() {
        loadAll()
    }
}

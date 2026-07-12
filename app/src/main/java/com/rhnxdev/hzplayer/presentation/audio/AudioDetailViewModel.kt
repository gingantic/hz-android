package com.rhnxdev.hzplayer.presentation.audio

import androidx.lifecycle.ViewModel
import androidx.compose.runtime.Stable
import androidx.lifecycle.viewModelScope
import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.AudioItem
import com.rhnxdev.hzplayer.domain.repository.AudioRepository
import com.rhnxdev.hzplayer.domain.repository.PlayerRepository
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Shared UI state for album and artist detail screens. */
@Stable
data class AudioDetailUiState(
    val title: String = "",
    val subtitle: String = "",
    val albumArtUri: String? = null,
    val songs: List<AudioItem> = emptyList(),
    /** Only populated for artist detail — the artist's albums. Empty for album detail. */
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = true,
)

@HiltViewModel
class AudioDetailViewModel @Inject constructor(
    private val audioRepository: AudioRepository,
    private val playerRepository: PlayerRepository,
) : ViewModel() {

    private val _uiState = MutableStateFlow(AudioDetailUiState())
    val uiState: StateFlow<AudioDetailUiState> = _uiState.asStateFlow()

    fun loadAlbum(title: String) {
        viewModelScope.launch {
            audioRepository.getSongsByAlbum(title)
                .catch { _uiState.update { it.copy(isLoading = false) } }
                .collect { songs ->
                    val sorted = songs.sortedBy { it.trackNumber }
                    _uiState.update {
                        it.copy(
                            title = title,
                            subtitle = subtitle(sorted.size, sorted.sumOf { s -> s.durationMs }),
                            albumArtUri = sorted.firstNotNullOfOrNull { s -> s.albumArtUri },
                            songs = sorted,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun loadArtist(name: String) {
        viewModelScope.launch {
            audioRepository.getSongsByArtist(name)
                .catch { _uiState.update { it.copy(isLoading = false) } }
                .collect { songs ->
                    // Derive the artist's albums from their songs (one card per distinct album).
                    val albums = songs
                        .filter { !it.album.isNullOrBlank() }
                        .groupBy { it.album!! }
                        .map { (albumTitle, albumSongs) ->
                            Album(
                                id = albumTitle.hashCode().toLong(),
                                title = albumTitle,
                                artist = name,
                                albumArtUri = albumSongs.firstNotNullOfOrNull { s -> s.albumArtUri },
                                trackCount = albumSongs.size,
                            )
                        }
                        .sortedBy { it.title.lowercase() }
                    _uiState.update {
                        it.copy(
                            title = name,
                            subtitle = artistSubtitle(albums.size, songs.size),
                            albumArtUri = songs.firstNotNullOfOrNull { s -> s.albumArtUri },
                            songs = songs.sortedBy { s -> s.title.lowercase() },
                            albums = albums,
                            isLoading = false,
                        )
                    }
                }
        }
    }

    fun onSongClicked(index: Int) {
        val songs = _uiState.value.songs
        if (index in songs.indices) playerRepository.playAudioPlaylist(songs, index)
    }

    fun onPlay() {
        val songs = _uiState.value.songs
        if (songs.isNotEmpty()) playerRepository.playAudioPlaylist(songs, 0)
    }

    fun onShuffle() {
        val songs = _uiState.value.songs
        if (songs.isNotEmpty()) playerRepository.playAudioPlaylist(songs.shuffled(), 0)
    }

    private fun subtitle(count: Int, totalMs: Long): String =
        "$count songs • ${com.rhnxdev.hzplayer.core.util.formatDuration(totalMs)}"

    private fun artistSubtitle(albumCount: Int, trackCount: Int): String =
        "$albumCount albums • $trackCount songs"
}

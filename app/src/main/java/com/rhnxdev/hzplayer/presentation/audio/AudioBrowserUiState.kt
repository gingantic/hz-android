package com.rhnxdev.hzplayer.presentation.audio

import com.rhnxdev.hzplayer.domain.model.Album
import com.rhnxdev.hzplayer.domain.model.Artist
import com.rhnxdev.hzplayer.domain.model.AudioItem

import androidx.compose.runtime.Immutable

enum class AudioTab(val label: String) {
    SONGS("Songs"),
    ALBUMS("Albums"),
    ARTISTS("Artists"),
}

@Immutable
data class AudioBrowserUiState(
    val currentTab: AudioTab = AudioTab.SONGS,
    val songs: List<AudioItem> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val isLoadingSongs: Boolean = true,
    val isLoadingAlbums: Boolean = true,
    val isLoadingArtists: Boolean = true,
    val error: String? = null,
    val filteredSongs: List<AudioItem> = emptyList(),
)
